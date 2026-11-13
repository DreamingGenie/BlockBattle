package com.blockbattle.room;

import java.security.Principal;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

/** STOMP 발행 /app/rooms/{code}/* (API 명세 5장). 참가자 여부는 StompAuthInterceptor가 먼저 확인한다. */
@Controller
public class RoomMessageController {

    public record ReadyRequest(Boolean ready) {
    }

    private final ReadyService ready;

    public RoomMessageController(ReadyService ready) {
        this.ready = ready;
    }

    /** 5.1 SEND /app/rooms/{code}/ready — payload {"ready": true} */
    @MessageMapping("/rooms/{code}/ready")
    public void ready(@DestinationVariable String code, @Payload ReadyRequest request, Principal user) {
        if (request == null || request.ready() == null) return;
        ready.setReady(code, Long.parseLong(user.getName()), request.ready());
    }
}
