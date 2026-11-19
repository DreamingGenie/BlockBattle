package com.blockbattle.realtime;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.AbstractBrokerMessageHandler;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpSubscription;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/** /topic/rooms/{code} 방송. 서버 발신 메시지는 모두 {type, roomCode, sentAt, payload} 봉투다(ENT-015). */
@Component
public class RoomEventPublisher {

    public record WsMessage(String type, String roomCode, long sentAt, Object payload) {
    }

    private final SimpMessagingTemplate messaging;
    private final Clock clock;
    private final SimpUserRegistry users;
    private final ObjectProvider<AbstractBrokerMessageHandler> broker;

    public RoomEventPublisher(SimpMessagingTemplate messaging, Clock clock, SimpUserRegistry users,
                              @Qualifier("simpleBrokerMessageHandler") ObjectProvider<AbstractBrokerMessageHandler> broker) {
        this.messaging = messaging;
        this.clock = clock;
        this.users = users;
        this.broker = broker;
    }

    public void publish(String roomCode, String type, Object payload) {
        publishAt(roomCode, type, payload, clock.millis());
    }

    /** sentAt을 정해 보낸다(COUNTDOWN의 startsAt = sentAt + 3,000ms처럼 payload가 전송 시각에 묶일 때) */
    public void publishAt(String roomCode, String type, Object payload, long sentAt) {
        messaging.convertAndSend("/topic/rooms/" + roomCode, new WsMessage(type, roomCode, sentAt, payload));
    }

    /**
     * 방을 나간 사용자의 /topic/rooms/{code} 구독을 브로커에서 지운다(보안 점검 H-2 잔여).
     * 구독 권한은 SUBSCRIBE 때만 검사하므로, 지우지 않으면 나간 뒤에도 그 방 방송을 계속 받는다.
     */
    public void unsubscribe(String roomCode, long userId) {
        SimpUser user = users.getUser(Long.toString(userId));
        if (user == null || !(broker.getIfAvailable() instanceof SimpleBrokerMessageHandler simple)) return;
        String destination = "/topic/rooms/" + roomCode;
        for (SimpSession session : user.getSessions()) {
            for (SimpSubscription subscription : session.getSubscriptions()) {
                if (!destination.equals(subscription.getDestination())) continue;
                SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.UNSUBSCRIBE);
                headers.setSessionId(session.getId());
                headers.setSubscriptionId(subscription.getId());
                simple.getSubscriptionRegistry().unregisterSubscription(
                        MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()));
            }
        }
    }
}
