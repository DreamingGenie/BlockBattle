package com.blockbattle.realtime;

import com.blockbattle.auth.jwt.JwtProvider;
import com.blockbattle.common.error.ApiException;
import com.blockbattle.common.error.ErrorCode;
import com.blockbattle.store.redis.RoomStateStore;
import java.security.Principal;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * STOMP 인증·권한(REQ-018, POL-013).
 * <ul>
 *   <li>CONNECT: Authorization 헤더의 Access JWT를 검사한다. 실패하면 StompAuthException → ERROR 프레임 후 연결 종료.</li>
 *   <li>SUBSCRIBE /topic/**, SEND /app/rooms/{code}/**: 방 참가자가 아니면 그 요청만 버리고 /user/queue/errors로 NOT_PARTICIPANT.</li>
 * </ul>
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    static final Pattern ROOM_TOPIC = Pattern.compile("^/topic/rooms/([A-Z0-9]{6})$");
    static final Pattern ROOM_SEND = Pattern.compile("^/app/rooms/([A-Z0-9]{6})/.+$");

    public record StompUser(String name) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }

    private final JwtProvider jwt;
    private final RoomStateStore rooms;
    private final ObjectProvider<SimpMessagingTemplate> messaging;

    public StompAuthInterceptor(JwtProvider jwt, RoomStateStore rooms, ObjectProvider<SimpMessagingTemplate> messaging) {
        this.jwt = jwt;
        this.rooms = rooms;
        this.messaging = messaging;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) return message;
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT) {
            accessor.setUser(new StompUser(Long.toString(authenticate(accessor.getFirstNativeHeader("Authorization")))));
            return message;
        }
        if (command != StompCommand.SUBSCRIBE && command != StompCommand.SEND) return message;
        Principal user = accessor.getUser();
        String destination = accessor.getDestination();
        if (user == null) throw new StompAuthException(ErrorCode.UNAUTHORIZED);
        if (destination == null || destination.startsWith("/user/")) return message;

        Matcher matcher = (command == StompCommand.SUBSCRIBE ? ROOM_TOPIC : ROOM_SEND).matcher(destination);
        boolean allowed = matcher.matches()
                && rooms.getPlayers(matcher.group(1)).containsKey(Long.parseLong(user.getName()));
        boolean guarded = command == StompCommand.SUBSCRIBE ? destination.startsWith("/topic/") : destination.startsWith("/app/rooms/");
        if (!guarded || allowed) return message;
        messaging.getObject().convertAndSendToUser(user.getName(), "/queue/errors", Map.of(
                "code", ErrorCode.NOT_PARTICIPANT.name(),
                "message", ErrorCode.NOT_PARTICIPANT.message(),
                "payload", Map.of("destination", destination)));
        return null; // 연결은 끊지 않고 이 요청만 무시한다
    }

    private long authenticate(String header) {
        if (header == null || !header.startsWith("Bearer ")) throw new StompAuthException(ErrorCode.UNAUTHORIZED);
        try {
            return jwt.verify(header.substring("Bearer ".length()).trim());
        } catch (ApiException e) {
            throw new StompAuthException(e.code());
        }
    }
}
