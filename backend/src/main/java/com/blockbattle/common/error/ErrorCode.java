package com.blockbattle.common.error;

import org.springframework.http.HttpStatus;

/** API 명세 6장 오류 코드. 작업마다 필요한 코드를 더한다. */
public enum ErrorCode {
    INVALID_NICKNAME(HttpStatus.BAD_REQUEST, "닉네임은 한글·영문·숫자 2~10자여야 합니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다. 다시 로그인해 주세요."),
    NICKNAME_TAG_EXHAUSTED(HttpStatus.CONFLICT, "이 닉네임은 더 만들 수 없습니다. 다른 닉네임을 써 주세요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
