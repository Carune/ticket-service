package com.ticket.api.scheduler;

import com.ticket.api.service.QueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueScheduler {

    private final QueueService queueService;

    @Value("${scheduler.queue.fetch-size:50}")
    private int fetchSize;

    @Scheduled(fixedDelayString = "${scheduler.queue.delay:1000}") // 딜레이도 설정으로 관리
    public void enterUsers() {
        // fetchSize는 "이번 tick에서 승급을 시도할 최대 인원"이다.
        // 실제 승급 수는 남은 active 슬롯(maxActive - currentActive)에 따라 더 작을 수 있다.
        long moved = queueService.allowUser(fetchSize);
        if (moved > 0) {
            log.info("스케줄러 입장 처리 - requested={}, moved={}", fetchSize, moved);
        }
    }
}
