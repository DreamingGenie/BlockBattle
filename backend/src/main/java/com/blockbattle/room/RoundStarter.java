package com.blockbattle.room;

import com.blockbattle.match.MatchRepository;
import com.blockbattle.realtime.RoomEventPublisher;
import com.blockbattle.store.redis.RoomPlayer;
import com.blockbattle.store.redis.RoomStateStore;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 친구 대전 라운드 시작(BR-02·BR-03): 새 시드로 matches 행(PLAYING)과 참가자 행을 만들고 COUNTDOWN을 보낸다.
 * 첫 라운드(준비 완료)·다음 라운드·무승부/ABORTED 재시도·재대결이 함께 쓴다. COUNTDOWN의 startsAt = sentAt + 3초.
 */
@Component
public class RoundStarter {

    public static final long COUNTDOWN_MS = 3_000;

    private final RoomStateStore rooms;
    private final RoomRepository roomRepository;
    private final MatchRepository matches;
    private final RoomEventPublisher events;
    private final SecureRandom random = new SecureRandom();

    public RoundStarter(RoomStateStore rooms, RoomRepository roomRepository, MatchRepository matches,
                        RoomEventPublisher events) {
        this.rooms = rooms;
        this.roomRepository = roomRepository;
        this.matches = matches;
        this.events = events;
    }

    /** startsAt에 시작하는 라운드를 만든다. 방 상태는 이미 PLAYING이어야 한다(호출하는 쪽이 CAS로 바꾼다) */
    public long start(String code, int seriesNo, int roundNo, int attemptNo, long startsAtMillis) {
        Map<String, String> room = rooms.getRoom(code);
        Map<Long, RoomPlayer> players = rooms.getPlayers(code);
        long roomId = Long.parseLong(room.get("roomId"));
        long hostId = Long.parseLong(room.get("hostId"));
        long seed = random.nextLong() >>> 32; // 라운드마다 새 시드(BR-03)
        Instant startsAt = Instant.ofEpochMilli(startsAtMillis);

        long matchId = matches.insertFriendMatch(roomId, seriesNo, roundNo, attemptNo, seed, startsAt);
        matches.insertPlayer(matchId, 1, hostId, "USER");
        // 방장이 1번, 나머지는 userId 순으로 2번부터(다인 대전 2026-11-19)
        List<Long> others = players.keySet().stream().filter(id -> id != hostId).sorted().toList();
        for (int i = 0; i < others.size(); i++) matches.insertPlayer(matchId, i + 2, others.get(i), "USER");
        roomRepository.updateStatus(roomId, "PLAYING");
        rooms.putRoomFields(code, Map.of("currentMatchId", Long.toString(matchId), "seriesNo", Integer.toString(seriesNo),
                "roundNo", Integer.toString(roundNo), "attemptNo", Integer.toString(attemptNo)));
        // 준비·재대결 표시는 라운드가 시작되면 되돌린다
        players.forEach((id, p) -> rooms.putPlayer(code, id, new RoomPlayer(p.nickname(), p.tag(), false, p.connected(), false)));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("matchId", matchId);
        payload.put("seriesNo", seriesNo);
        payload.put("roundNo", roundNo);
        payload.put("attemptNo", attemptNo);
        payload.put("seed", seed);
        payload.put("startsAt", startsAtMillis);
        events.publishAt(code, "COUNTDOWN", payload, startsAtMillis - COUNTDOWN_MS);
        return matchId;
    }
}
