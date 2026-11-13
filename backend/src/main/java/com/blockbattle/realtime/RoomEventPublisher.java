package com.blockbattle.realtime;

import java.time.Clock;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** /topic/rooms/{code} 방송. 서버 발신 메시지는 모두 {type, roomCode, sentAt, payload} 봉투다(ENT-015). */
@Component
public class RoomEventPublisher {

    public record WsMessage(String type, String roomCode, long sentAt, Object payload) {
    }

    private final SimpMessagingTemplate messaging;
    private final Clock clock;

    public RoomEventPublisher(SimpMessagingTemplate messaging, Clock clock) {
        this.messaging = messaging;
        this.clock = clock;
    }

    public void publish(String roomCode, String type, Object payload) {
        publishAt(roomCode, type, payload, clock.millis());
    }

    /** sentAt을 정해 보낸다(COUNTDOWN의 startsAt = sentAt + 3,000ms처럼 payload가 전송 시각에 묶일 때) */
    public void publishAt(String roomCode, String type, Object payload, long sentAt) {
        messaging.convertAndSend("/topic/rooms/" + roomCode, new WsMessage(type, roomCode, sentAt, payload));
    }
}
