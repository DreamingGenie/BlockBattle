package com.blockbattle.room;

import com.blockbattle.match.MatchRepository;
import com.blockbattle.realtime.RoomEventPublisher;
import com.blockbattle.store.redis.RoomPlayer;
import com.blockbattle.store.redis.RoomStateStore;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
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
    static final long COUNTDOWN_MS = 3_000;

    private final RoomStateStore rooms;
    private final RoomRepository roomRepository;
    private final MatchRepository matches;
    private final RoomEventPublisher events;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ReadyService(RoomStateStore rooms, RoomRepository roomRepository, MatchRepository matches,
                        RoomEventPublisher events, Clock clock) {
        this.rooms = rooms;
        this.roomRepository = roomRepository;
        this.matches = matches;
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
        boolean allReady = players.size() == RoomService.CAPACITY && players.values().stream().allMatch(RoomPlayer::ready);
        if (allReady && rooms.compareAndSetStatus(code, "WAITING", "PLAYING")) {
            startCountdown(code, rooms.getRoom(code), players);
        }
    }

    private void startCountdown(String code, Map<String, String> room, Map<Long, RoomPlayer> players) {
        long roomId = Long.parseLong(room.get("roomId"));
        long hostId = Long.parseLong(room.get("hostId"));
        int seriesNo = Integer.parseInt(room.getOrDefault("seriesNo", "1"));
        int roundNo = Integer.parseInt(room.getOrDefault("roundNo", "1"));
        int attemptNo = Integer.parseInt(room.getOrDefault("attemptNo", "1"));
        long seed = random.nextLong() >>> 32;
        long sentAt = clock.millis();
        Instant startsAt = Instant.ofEpochMilli(sentAt + COUNTDOWN_MS);

        long matchId = matches.insertFriendMatch(roomId, seriesNo, roundNo, attemptNo, seed, startsAt);
        matches.insertPlayer(matchId, 1, hostId, "USER");
        players.keySet().stream().filter(id -> id != hostId).findFirst()
                .ifPresent(guestId -> matches.insertPlayer(matchId, 2, guestId, "USER"));
        roomRepository.updateStatus(roomId, "PLAYING");
        rooms.putRoomFields(code, Map.of("currentMatchId", Long.toString(matchId)));
        // 다음 라운드를 위해 준비 표시를 되돌린다
        players.forEach((id, p) -> rooms.putPlayer(code, id, new RoomPlayer(p.nickname(), p.tag(), false, p.connected(), p.rematch())));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("matchId", matchId);
        payload.put("seriesNo", seriesNo);
        payload.put("roundNo", roundNo);
        payload.put("attemptNo", attemptNo);
        payload.put("seed", seed);
        payload.put("startsAt", startsAt.toEpochMilli());
        events.publishAt(code, "COUNTDOWN", payload, sentAt);
    }
}
