package com.blockbattle.room;

import com.blockbattle.battle.LockService;
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
    private final LockService locks;

    public RoomMessageController(ReadyService ready, LockService locks) {
        this.ready = ready;
        this.locks = locks;
    }

    /** 5.2 SEND /app/rooms/{code}/lock — 서버 검증·재계산 뒤 BOARD·ATTACK 방송(BR-05) */
    @MessageMapping("/rooms/{code}/lock")
    public void lock(@DestinationVariable String code, @Payload LockService.LockMessage message, Principal user) {
        locks.handle(code, Long.parseLong(user.getName()), message);
    }

    public record TopoutRequest(Integer seq) {
    }

    /** 5.3 SEND /app/rooms/{code}/topout — 서버 보드로 확인한 뒤 BR-04 판정(T-13) */
    @MessageMapping("/rooms/{code}/topout")
    public void topout(@DestinationVariable String code, @Payload TopoutRequest request, Principal user) {
        locks.topout(code, Long.parseLong(user.getName()), request == null ? null : request.seq());
    }

    /** 5.1 SEND /app/rooms/{code}/ready — payload {"ready": true} */
    @MessageMapping("/rooms/{code}/ready")
    public void ready(@DestinationVariable String code, @Payload ReadyRequest request, Principal user) {
        if (request == null || request.ready() == null) return;
        ready.setReady(code, Long.parseLong(user.getName()), request.ready());
    }
}
