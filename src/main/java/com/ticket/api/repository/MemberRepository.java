package com.ticket.api.repository;

import com.ticket.api.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MemberRepository extends JpaRepository<Member, Long> {

    // 이메일로 회원 찾기 (로그인 / 중복 검사)
    Optional<Member> findByEmail(String email);
}