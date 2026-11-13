package com.blockbattle.common.error;

import org.springframework.http.HttpStatus;

/** API 명세 6장 오류 코드. 작업마다 필요한 코드를 더한다. */
public enum ErrorCode {
    INVALID_NICKNAME(HttpStatus.BAD_REQUEST, "닉네임은 한글·영문·숫자 2~10자여야 합니다."),
    INVALID_MODE(HttpStatus.BAD_REQUEST, "경기 모드나 난이도가 올바르지 않습니다."),
    INVALID_RESULT(HttpStatus.BAD_REQUEST, "경기 결과 형식이 올바르지 않습니다."),
    NOT_PARTICIPANT(HttpStatus.FORBIDDEN, "참가하지 않은 경기입니다."),
    MATCH_NOT_FOUND(HttpStatus.NOT_FOUND, "경기를 찾을 수 없습니다."),
    MATCH_ALREADY_FINISHED(HttpStatus.CONFLICT, "이미 끝난 경기입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다. 다시 로그인해 주세요."),
    KAKAO_AUTH_FAILED(HttpStatus.UNAUTHORIZED, "카카오 인증에 실패했습니다."),
    KAKAO_NOT_LINKED(HttpStatus.NOT_FOUND, "연결된 계정이 없는 카카오 계정입니다."),
    NICKNAME_TAG_EXHAUSTED(HttpStatus.CONFLICT, "이 닉네임은 더 만들 수 없습니다. 다른 닉네임을 써 주세요."),
    KAKAO_ALREADY_LINKED(HttpStatus.CONFLICT, "이미 다른 계정에 연결된 카카오 계정입니다."),
    ACCOUNT_ALREADY_LINKED(HttpStatus.CONFLICT, "이미 카카오가 연결된 계정입니다."),
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
