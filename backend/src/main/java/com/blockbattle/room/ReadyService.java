package com.blockbattle.room;

import com.blockbattle.realtime.RoomEventPublisher;
import com.blockbattle.store.redis.RoomPlayer;
import com.blockbattle.store.redis.RoomStateStore;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 준비·카운트다운(FR-ROM-03, REQ-011). 준비는 활동이라 방 TTL을 30분으로 되돌린다(POL-024).
 * 두 참가자가 모두 준비되면 방 상태를 WAITING→PLAYING으로 한 번만 바꾼 쪽이 경기 행을 만들고 COUNTDOWN을 보낸다.
 */
@Service
public class ReadyService {

    private static final Logger log = LoggerFactory.getLogger(ReadyService.class);
    private final RoomStateStore rooms;
    private final RoundStarter roundStarter;
    private final RoomEventPublisher events;
    private final Clock clock;

    public ReadyService(RoomStateStore rooms, RoundStarter roundStarter, RoomEventPublisher events, Clock clock) {
        this.rooms = rooms;
        this.roundStarter = roundStarter;
        this.events = events;
        this.clock = clock;
    }

    public void setReady(String code, long userId, boolean ready) {
        Map<String, String> room = rooms.getRoom(code);
        if (!"WAITING".equals(room.get("status"))) {
            log.info("WAITING이 아닌 방의 준비 요청을 무시한다 room={} user={}", code, userId);
            return;
        }
        RoomPlayer me = rooms.getPlayers(code).get(userId);
        if (me == null) return;
        if (me.ready() != ready) {
            rooms.putPlayer(code, userId, new RoomPlayer(me.nickname(), me.tag(), ready, me.connected(), me.rematch()));
            events.publish(code, "READY_CHANGED", Map.of("userId", userId, "ready", ready));
        }
        rooms.touch(code);

        Map<Long, RoomPlayer> players = rooms.getPlayers(code);
        // 2명 이상이 모두 준비하면 시작한다. 방장 화면의 준비 버튼은 "시작"이라, 다른 사람이 모두 준비한 뒤에만 눌린다(다인 대전 2026-11-19)
        boolean allReady = players.size() >= 2 && players.values().stream().allMatch(RoomPlayer::ready);
        if (allReady && rooms.compareAndSetStatus(code, "WAITING", "PLAYING")) {
            startCountdown(code, rooms.getRoom(code));
        }
    }

    private void startCountdown(String code, Map<String, String> room) {
        roundStarter.start(code, Integer.parseInt(room.getOrDefault("seriesNo", "1")),
                Integer.parseInt(room.getOrDefault("roundNo", "1")), Integer.parseInt(room.getOrDefault("attemptNo", "1")),
                clock.millis() + RoundStarter.COUNTDOWN_MS);
    }
}
