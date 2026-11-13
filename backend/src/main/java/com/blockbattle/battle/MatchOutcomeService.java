package com.blockbattle.battle;

import com.blockbattle.match.MatchRepository;
import com.blockbattle.match.MatchRepository.PlayerStats;
import com.blockbattle.realtime.RoomEventPublisher;
import com.blockbattle.room.RoomRepository;
import com.blockbattle.store.redis.DisconnectTimerStore;
import com.blockbattle.store.redis.RoomStateStore;
import com.blockbattle.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 친구 대전 라운드 종료(BR-04·BR-09, POL-004·POL-025).
 * matches(상태·사유·승자·시간)와 match_players 성적(Redis 마지막 상태)·전적을 한 트랜잭션으로 확정하고, 커밋 뒤 방 상태와 GAME_OVER를 처리한다.
 * matches는 status='PLAYING'일 때만 바뀌므로 topout·퇴장·끊김이 겹쳐도 한 번만 확정된다.
 */
@Service
public class MatchOutcomeService {

    public enum Reason { TOP_OUT, DRAW, LEFT, DISCONNECT, ABORTED }

    private final MatchRepository matches;
    private final RoomRepository roomRepository;
    private final UserRepository users;
    private final PlayerStateRepository states;
    private final RoomStateStore rooms;
    private final DisconnectTimerStore timers;
    private final RoomEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public MatchOutcomeService(MatchRepository matches, RoomRepository roomRepository, UserRepository users,
                               PlayerStateRepository states, RoomStateStore rooms, DisconnectTimerStore timers,
                               RoomEventPublisher events, TransactionTemplate tx, Clock clock) {
        this.matches = matches;
        this.roomRepository = roomRepository;
        this.users = users;
        this.states = states;
        this.rooms = rooms;
        this.timers = timers;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    /** loserId: TOP_OUT·LEFT·DISCONNECT에서 진 사람. DRAW·ABORTED는 null. 이미 끝난 경기면 false */
    public boolean finish(String code, long matchId, Reason reason, Long loserId) {
        var match = matches.findFriendMatch(matchId).orElse(null);
        if (match == null) return false;
        Map<String, String> room = rooms.getRoom(code);
        int roundCount = Integer.parseInt(room.getOrDefault("roundCount", "1"));
        Long winnerId = loserId == null ? null
                : match.slots().keySet().stream().filter(id -> !id.equals(loserId)).findFirst().orElse(null);
        boolean aborted = reason == Reason.ABORTED;
        // 시리즈 승자: 1판 방은 라운드 승자가 곧 시리즈 승자. 3판 2선승 진행은 T-14
        Long seriesWinnerId = roundCount == 1 ? winnerId : null;
        Instant now = clock.instant();
        int durationMs = (int) Math.max(0, Duration.between(match.startedAt(), now).toMillis());
        Map<Long, PlayerState> finalStates = new LinkedHashMap<>();
        match.slots().keySet().forEach(id -> finalStates.put(id, states.load(matchId, id)));

        Boolean changed = tx.execute(status -> {
            if (!matches.finishFriend(matchId, aborted ? "ABORTED" : "FINISHED",
                    aborted ? "DISCONNECT" : reason.name(), winnerId, seriesWinnerId, now, durationMs)) {
                return false;
            }
            match.slots().forEach((userId, slot) -> {
                PlayerState s = finalStates.get(userId);
                String result = winnerId == null ? "NONE" : winnerId.equals(userId) ? "WIN" : "LOSE";
                matches.updatePlayer(matchId, slot, result, new PlayerStats(s.score(), s.linesCleared(), s.level(),
                        s.piecesPlaced(), s.attackSent(), s.attackReceived(), s.maxCombo()));
            });
            if (!aborted && winnerId != null) { // 무승부·ABORTED·NONE은 전적을 바꾸지 않는다(BR-09)
                users.recordFriendResult(winnerId, true, now);
                users.recordFriendResult(loserId, false, now);
            }
            return true;
        });
        if (!Boolean.TRUE.equals(changed)) return false;

        // 커밋 뒤 진행 상태(Redis)와 방 이력
        match.slots().keySet().forEach(id -> timers.cancel(matchId, id));
        if (winnerId != null) rooms.incrementWins(code, winnerId);
        rooms.putRoomFields(code, Map.of("currentMatchId", ""));
        if (seriesWinnerId != null && rooms.compareAndSetStatus(code, "PLAYING", "FINISHED")) {
            roomRepository.updateStatus(match.roomId(), "FINISHED");
        }
        if (aborted) return true; // 양쪽 모두 끊겨 받을 사람이 없다(T-13 계획 unlinked)

        Map<String, Object> seriesWins = new LinkedHashMap<>();
        Map<String, String> after = rooms.getRoom(code);
        match.slots().keySet().forEach(id -> seriesWins.put(Long.toString(id),
                Integer.parseInt(after.getOrDefault("wins:" + id, "0"))));
        List<Map<String, Object>> stats = new ArrayList<>();
        finalStates.forEach((id, s) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", id);
            row.put("score", s.score());
            row.put("attackSent", s.attackSent());
            row.put("attackReceived", s.attackReceived());
            row.put("maxCombo", s.maxCombo());
            row.put("apm", apm(s.attackSent(), durationMs));
            stats.add(row);
        });
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("matchId", matchId);
        payload.put("winnerUserId", winnerId);
        payload.put("reason", reason.name());
        payload.put("seriesWins", seriesWins);
        payload.put("seriesWinnerUserId", seriesWinnerId);
        payload.put("nextRoundStartsAt", null);
        payload.put("stats", stats);
        events.publish(code, "GAME_OVER", payload);
        return true;
    }

    /** APM = 보낸 공격 줄 ÷ 플레이 분, 소수 첫째 자리(요구사항 용어) */
    static double apm(int attackSent, int durationMs) {
        if (durationMs <= 0) return 0;
        return Math.round(attackSent / (durationMs / 60_000.0) * 10) / 10.0;
    }
}
