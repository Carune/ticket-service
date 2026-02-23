package com.ticket.api.service;

import com.ticket.api.exception.TooManyRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String WAITING_KEY = "waiting_queue";
    private static final String ACTIVE_KEY_PREFIX = "active:user:";
    private static final String ACTIVE_USERS_KEY = "active_users";
    private static final String QUEUE_SEQUENCE_KEY = "queue:seq";
    private static final String RANK_THROTTLE_PREFIX = "throttle:rank:";

    @Value("${queue.active-ttl-seconds:300}")
    private int activeTtlSeconds;

    @Value("${queue.max-active-users:1000}")
    private long maxActiveUsers;

    @Value("${queue.rank-throttle-seconds:3}")
    private int rankThrottleSeconds;

    /**
     * Waiting -> Active 전환을 Redis 단일 명령(Lua)으로 처리한다.
     * - ZPOPMIN(대기열 제거) + SETEX(활성화 키 생성) + ZADD(active_users 등록)를 한 번에 수행
     * - 명령 사이 중간 실패로 인한 유실을 방지
     */
    private static final DefaultRedisScript<Long> ACTIVATE_USERS_SCRIPT = createActivateUsersScript();

    private static DefaultRedisScript<Long> createActivateUsersScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText(
                "local waitingKey = KEYS[1]\n" +
                "local activeUsersKey = KEYS[2]\n" +
                "local activePrefix = ARGV[1]\n" +
                "local ttlSeconds = tonumber(ARGV[2])\n" +
                "local expireAtMillis = tonumber(ARGV[3])\n" +
                "local limit = tonumber(ARGV[4])\n" +
                "local moved = 0\n" +
                "for i = 1, limit do\n" +
                "  local popped = redis.call('ZPOPMIN', waitingKey, 1)\n" +
                "  if (not popped) or (#popped == 0) then\n" +
                "    break\n" +
                "  end\n" +
                "  local userId = popped[1]\n" +
                "  redis.call('SETEX', activePrefix .. userId, ttlSeconds, 'true')\n" +
                "  redis.call('ZADD', activeUsersKey, expireAtMillis, userId)\n" +
                "  moved = moved + 1\n" +
                "end\n" +
                "return moved"
        );
        return script;
    }

    /*
     * 대기열 등록 (진입)
     * - Redis Sorted Set 사용 (Score: 전역 시퀀스)
     * - FIFO
     * - 상태 정책: WAITING(ZSET) -> ACTIVE(KEY, TTL)
     *   ACTIVE 키가 살아있는 동안은 재진입 불가, 만료 후 재진입 가능
     */
    public void addQueue(String userId) {
        // 이미 입장 가능한 상태인지 확인 (Active Queue)
        Boolean isActive = redisTemplate.hasKey(ACTIVE_KEY_PREFIX + userId);
        if (Boolean.TRUE.equals(isActive)) {
            throw new IllegalStateException("이미 입장 처리된 사용자입니다.");
        }

        // 이미 대기열에 있는지 확인 (Waiting Queue)
        Double score = redisTemplate.opsForZSet().score(WAITING_KEY, userId);
        if (score != null) {
            throw new IllegalStateException("이미 대기열에 등록되어 있습니다.");
        }

        // 핵심: timestamp 대신 INCR 시퀀스를 score로 사용해 "동일 ms 충돌"을 제거한다.
        // score가 작을수록 먼저 진입한 사용자이며, 엄밀한 진입순(FIFO)을 보장한다.
        Long sequence = redisTemplate.opsForValue().increment(QUEUE_SEQUENCE_KEY);
        if (sequence == null) {
            throw new IllegalStateException("대기열 시퀀스 생성에 실패했습니다.");
        }

        // ZADD waiting_queue {sequence} {userId}
        redisTemplate.opsForZSet().add(WAITING_KEY, userId, sequence.doubleValue());

        log.info("대기열 등록 완료 - User: {}, Seq: {}", userId, sequence);
    }

    /*
     * 대기열 순번 조회
     * - 0부터 시작하므로 +1
     * - return: 앞에 대기 인원 수(=현재 순서)
     */
    public Long getRank(String userId) {
        // "throttle:rank:userID" 키를 일정 시간 동안만 유지
        String throttleKey = RANK_THROTTLE_PREFIX + userId;
        Boolean isPass = redisTemplate.opsForValue()
                .setIfAbsent(throttleKey, "check", Duration.ofSeconds(rankThrottleSeconds));

        if (Boolean.FALSE.equals(isPass)) {
            throw new TooManyRequestException("잠시 후 다시 시도해주세요. (" + rankThrottleSeconds + "초 대기)");
        }
        // 이미 입장 가능한 상태인지 먼저 확인
        if (isAllowed(userId)) {
            return 0L; // 0이면 바로 입장 접속
        }

        // 대기열 순번 확인
        Long rank = redisTemplate.opsForZSet().rank(WAITING_KEY, userId);

        if (rank == null) {
            return -1L;
        }

        return rank + 1;
    }

    // n명의 사용자를 대기열에서 꺼내 활성 상태로 전환
    public long allowUser(long count) {
        if (count <= 0) {
            return 0L;
        }

        long nowMillis = System.currentTimeMillis();
        long expireAtMillis = nowMillis + (activeTtlSeconds * 1000L);

        // active_users는 "현재 활성 사용자 수 계산" 용도다.
        // SETEX는 TTL이 지나면 자동 삭제되지만, ZSET 멤버는 자동 삭제되지 않으므로 주기적으로 정리한다.
        cleanupExpiredActiveUsers(nowMillis);

        Long activeCount = redisTemplate.opsForZSet().zCard(ACTIVE_USERS_KEY);
        long currentActive = activeCount == null ? 0L : activeCount;
        long availableSlots = maxActiveUsers - currentActive;
        if (availableSlots <= 0) {
            log.debug("활성화 가능한 슬롯 없음 - currentActive={}, maxActive={}", currentActive, maxActiveUsers);
            return 0L;
        }

        long moveLimit = Math.min(count, availableSlots);

        // Lua로 Waiting->Active를 원자적으로 전환
        Long moved = redisTemplate.execute(
                ACTIVATE_USERS_SCRIPT,
                List.of(WAITING_KEY, ACTIVE_USERS_KEY),
                ACTIVE_KEY_PREFIX,
                String.valueOf(activeTtlSeconds),
                String.valueOf(expireAtMillis),
                String.valueOf(moveLimit)
        );

        long movedCount = moved == null ? 0L : moved;
        if (movedCount > 0) {
            log.info(
                    "입장 처리 완료 - moved={}, requested={}, availableSlots={}, currentActive={}, maxActive={}",
                    movedCount, count, availableSlots, currentActive, maxActiveUsers
            );
        }
        return movedCount;
    }

    // 사용자가 활성 상태인지(입장 가능한지) 확인
    public boolean isAllowed(String userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(ACTIVE_KEY_PREFIX + userId));
    }

    // 대기열 제거
    public void removeQueue(String userId) {
        redisTemplate.opsForZSet().remove(WAITING_KEY, userId);
    }

    private void cleanupExpiredActiveUsers(long nowMillis) {
        // score(만료 시각)가 현재 시각보다 작거나 같은 멤버 제거
        redisTemplate.opsForZSet().removeRangeByScore(ACTIVE_USERS_KEY, 0, nowMillis);
    }
}
