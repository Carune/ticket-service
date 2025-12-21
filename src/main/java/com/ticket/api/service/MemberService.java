package com.ticket.api.service;

import com.ticket.api.dto.MemberResponse;
import com.ticket.api.dto.SignUpRequest;

public interface MemberService {
    public Long signUp(SignUpRequest signUpRequest); // 회원가입
    public MemberResponse findMember(String email); // 회원찾기
}
