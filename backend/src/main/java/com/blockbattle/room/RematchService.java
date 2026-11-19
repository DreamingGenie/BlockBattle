package com.blockbattle.room;

import com.blockbattle.realtime.RoomEventPublisher;
import com.blockbattle.store.redis.RoomPlayer;
import com.blockbattle.store.redis.RoomStateStore;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 재대결(REQ-014, FR-BAT-03, API 명세 5.4). 방이 FINISHED일 때만 받는다.
 * 수락은 활동이라 방 TTL을 30분으로 되돌린다(POL-024). 두 참가자가 모두 수락하면 FINISHED→PLAYING을 한 번만 바꾼 쪽이
 * series_no + 1, 라운드 1·시도 1·승수 0으로 새 시리즈 첫 라운드를 시작한다. 한쪽이 거절하면 그 시리즈의 재대결은 끝난다.
 */
@Service
public class RematchService {

    static final String CLOSED_FIELD = "rematchClosed";

    private final RoomStateStore rooms;
    private final RoundStarter roundStarter;
    private final RoomEventPublisher events;
    private final Clock clock;

    public RematchService(RoomStateStore rooms, RoundStarter roundStarter, RoomEventPublisher events, Clock clock) {
        this.rooms = rooms;
        this.roundStarter = roundStarter;
        this.events = events;
        this.clock = clock;
    }

    public void respond(String code, long userId, boolean accept) {
        Map<String, String> room = rooms.getRoom(code);
        if (!"FINISHED".equals(room.get("status")) || "1".equals(room.get(CLOSED_FIELD))) return;
        RoomPlayer me = rooms.getPlayers(code).get(userId);
        if (me == null) return;
        rooms.putPlayer(code, userId, new RoomPlayer(me.nickname(), me.tag(), me.ready(), me.connected(), accept));
        if (!accept) rooms.putRoomFields(code, Map.of(CLOSED_FIELD, "1"));
        else rooms.touch(code);
        events.publish(code, "REMATCH_CHANGED", Map.of("userId", userId, "accept", accept));
        if (!accept) return;

        Map<Long, RoomPlayer> players = rooms.getPlayers(code);
        boolean bothAccepted = players.size() >= 2 && players.values().stream().allMatch(RoomPlayer::rematch); // 남은 사람 모두
        if (!bothAccepted || !rooms.compareAndSetStatus(code, "FINISHED", "PLAYING")) return;
        int seriesNo = Integer.parseInt(room.getOrDefault("seriesNo", "1")) + 1;
        Map<String, String> reset = new HashMap<>();
        players.keySet().forEach(id -> reset.put(RoomStateStore.WINS_FIELD_PREFIX + id, "0"));
        reset.put(CLOSED_FIELD, "");
        rooms.putRoomFields(code, reset);
        roundStarter.start(code, seriesNo, 1, 1, clock.millis() + RoundStarter.COUNTDOWN_MS);
    }
}
