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
        messaging.convertAndSend("/topic/rooms/" + roomCode, new WsMessage(type, roomCode, clock.millis(), payload));
    }
}
