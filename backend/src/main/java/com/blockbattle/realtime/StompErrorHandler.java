package com.blockbattle.realtime;

import com.blockbattle.common.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * CONNECT 거부 ERROR 프레임(POL-013): message 헤더에 UNAUTHORIZED 또는 TOKEN_EXPIRED, 본문에 {"code","message"} JSON.
 * ERROR 프레임을 보낸 뒤 서버는 연결을 닫는다(STOMP 규약).
 */
public class StompErrorHandler extends StompSubProtocolErrorHandler {

    private final ObjectMapper json;

    public StompErrorHandler(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
        ErrorCode code = findCode(ex);
        if (code == null) return super.handleClientMessageProcessingError(clientMessage, ex);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(code.name());
        accessor.setContentType(org.springframework.util.MimeTypeUtils.APPLICATION_JSON);
        accessor.setLeaveMutable(true);
        byte[] body;
        try {
            body = json.writeValueAsBytes(Map.of("code", code.name(), "message", code.message()));
        } catch (JsonProcessingException e) {
            body = code.name().getBytes(StandardCharsets.UTF_8);
        }
        return MessageBuilder.createMessage(body, accessor.getMessageHeaders());
    }

    private static ErrorCode findCode(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof StompAuthException auth) return auth.code();
        }
        return null;
    }
}
