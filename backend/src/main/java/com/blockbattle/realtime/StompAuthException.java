package com.blockbattle.realtime;

import com.blockbattle.common.error.ErrorCode;
import org.springframework.messaging.MessagingException;

/** CONNECT 인증 실패. 오류 처리기가 message 헤더·본문을 이 코드로 채운 ERROR 프레임을 만든다(POL-013). */
public class StompAuthException extends MessagingException {

    private final ErrorCode code;

    public StompAuthException(ErrorCode code) {
        super(code.name());
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
