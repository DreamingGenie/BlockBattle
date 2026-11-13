package com.blockbattle.battle;

import com.blockbattle.realtime.RoomEventPublisher;
import com.blockbattle.realtime.RoomEventPublisher.WsMessage;
import com.blockbattle.store.redis.DisconnectTimerStore;
import com.blockbattle.store.redis.RoomPlayer;
import com.blockbattle.store.redis.RoomStateStore;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/**
 * 경기 중 연결 끊김·재접속(BR-06, POL-006·POL-025, API 명세 5.5·5.7).
 * 끊기면 connected=false, PLAYER_LEFT(DISCONNECTED), 10초 타이머. 끊긴 쪽 보드는 lock이 없어 그대로이고 상대 lock·공격은 계속 처리된다.
 * 10초 안 재접속이면 PLAYER_RECONNECTED와 /user/queue/state 구독 때 RESYNC, 아니면 DISCONNECT 패배, 둘 다 끊겨 있으면 ABORTED.
 * 같은 사용자가 새 세션을 먼저 연 뒤 옛 세션이 끊기는 경우를 위해 사용자별 열린 세션을 센다(단일 서버 기준).
 */
@Component
public class ConnectionEvents {

    static final Duration GRACE = Duration.ofSeconds(10);
    static final Duration RESYNC_DELAY = Duration.ofMillis(100); // 구독 등록이 끝난 뒤 보낸다

    private final RoomStateStore rooms;
    private final DisconnectTimerStore timers;
    private final PlayerStateRepository states;
    private final RoomEventPublisher events;
    private final SimpMessagingTemplate messaging;
    private final MatchOutcomeService outcomes;
    private final BattleScheduler scheduler;
    private final Clock clock;
    private final Map<Long, Set<String>> openSessions = new ConcurrentHashMap<>();

    public ConnectionEvents(RoomStateStore rooms, DisconnectTimerStore timers, PlayerStateRepository states,
                            RoomEventPublisher events, SimpMessagingTemplate messaging, MatchOutcomeService outcomes,
                            BattleScheduler scheduler, Clock clock) {
        this.rooms = rooms;
        this.timers = timers;
        this.states = states;
        this.events = events;
        this.messaging = messaging;
        this.outcomes = outcomes;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    private record Playing(String code, long matchId) {
    }

    /** 사용자가 진행 중 경기에 있으면 방 코드와 경기 번호 */
    private Playing playing(long userId) {
        String code = rooms.findUserRoom(userId).orElse(null);
        if (code == null) return null;
        Map<String, String> room = rooms.getRoom(code);
        String current = room.get("currentMatchId");
        if (!"PLAYING".equals(room.get("status")) || current == null || current.isBlank()) return null;
        return new Playing(code, Long.parseLong(current));
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        Long userId = userId(event.getUser());
        if (userId == null) return;
        openSessions.computeIfAbsent(userId, id -> ConcurrentHashMap.newKeySet())
                .add(StompHeaderAccessor.wrap(event.getMessage()).getSessionId());
        // REST 입장 직후 connected=false로 시작하므로 STOMP 연결 때 true로 바꾼다. 경기 중이면 끊겼다가 다시 붙은 것이다
        String code = rooms.findUserRoom(userId).orElse(null);
        if (code == null) return;
        RoomPlayer me = rooms.getPlayers(code).get(userId);
        if (me == null || me.connected()) return;
        rooms.putPlayer(code, userId, new RoomPlayer(me.nickname(), me.tag(), me.ready(), true, me.rematch()));
        Playing p = playing(userId);
        if (p == null) return;
        timers.cancel(p.matchId(), userId);
        events.publish(p.code(), "PLAYER_RECONNECTED", Map.of("userId", userId));
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        Long userId = userId(event.getUser());
        if (userId == null) return;
        Set<String> open = openSessions.get(userId);
        if (open != null) {
            open.remove(event.getSessionId());
            if (!open.isEmpty()) return; // 다른 세션으로 이미 다시 붙어 있다
        }
        String code = rooms.findUserRoom(userId).orElse(null);
        if (code == null) return;
        RoomPlayer me = rooms.getPlayers(code).get(userId);
        if (me == null || !me.connected()) return;
        rooms.putPlayer(code, userId, new RoomPlayer(me.nickname(), me.tag(), me.ready(), false, me.rematch()));
        Playing p = playing(userId);
        if (p == null) return; // 대기 중 끊김은 표시만 바꾼다(유예·패배는 경기 중에만, BR-06)
        timers.start(p.matchId(), userId);
        events.publish(p.code(), "PLAYER_LEFT", Map.of("userId", userId, "reason", "DISCONNECTED"));
        scheduler.after(GRACE, () -> expire(p.code(), p.matchId(), userId));
    }

    /** 10초 뒤: 아직 끊겨 있으면 DISCONNECT 패배, 상대도 끊겨 있으면 ABORTED */
    void expire(String code, long matchId, long userId) {
        if (!Long.toString(matchId).equals(rooms.getRoom(code).get("currentMatchId"))) return;
        Map<Long, RoomPlayer> players = rooms.getPlayers(code);
        RoomPlayer me = players.get(userId);
        if (me == null || me.connected()) return;
        boolean opponentAlsoGone = players.entrySet().stream()
                .filter(e -> e.getKey() != userId)
                .allMatch(e -> !e.getValue().connected());
        if (opponentAlsoGone) {
            outcomes.finish(code, matchId, MatchOutcomeService.Reason.ABORTED, null);
        } else {
            outcomes.finish(code, matchId, MatchOutcomeService.Reason.DISCONNECT, userId);
        }
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        Long userId = userId(event.getUser());
        if (userId == null) return;
        if (!"/user/queue/state".equals(StompHeaderAccessor.wrap(event.getMessage()).getDestination())) return;
        scheduler.after(RESYNC_DELAY, () -> sendResync(userId));
    }

    private void sendResync(long userId) {
        Playing p = playing(userId);
        if (p == null) return;
        PlayerState me = states.load(p.matchId(), userId);
        long opponentId = rooms.getPlayers(p.code()).keySet().stream().filter(id -> id != userId).findFirst().orElse(-1L);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("matchId", p.matchId());
        payload.put("seq", me.seq());
        payload.put("pieceIndex", me.pieceIndex());
        payload.put("hold", me.hold() == null ? null : me.hold().name());
        payload.put("holdUsed", me.holdUsed());
        payload.put("board", me.board().toString());
        payload.put("pendingGarbage", List.copyOf(me.pending()));
        if (opponentId > 0) {
            PlayerState opp = states.load(p.matchId(), opponentId);
            payload.put("opponent", Map.of("userId", opponentId, "board", opp.board().toString(), "score", opp.score()));
        }
        messaging.convertAndSendToUser(Long.toString(userId), "/queue/state",
                new WsMessage("RESYNC", p.code(), clock.millis(), payload));
    }

    private static Long userId(Principal user) {
        if (user == null) return null;
        try {
            return Long.parseLong(user.getName());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
