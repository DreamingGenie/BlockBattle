package com.blockbattle.battle;

import com.blockbattle.match.Apm;
import com.blockbattle.match.BotCheck;
import com.blockbattle.match.MatchRepository;
import com.blockbattle.match.MatchRepository.PlayerStats;
import com.blockbattle.realtime.RoomEventPublisher;
import com.blockbattle.room.RoomRepository;
import com.blockbattle.room.RoundStarter;
import com.blockbattle.store.redis.DisconnectTimerStore;
import com.blockbattle.store.redis.RoomPlayer;
import com.blockbattle.store.redis.RoomStateStore;
import com.blockbattle.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 친구 대전 라운드 종료와 시리즈 진행(BR-03·BR-04·BR-09, POL-003·POL-004·POL-025).
 * matches(상태·사유·승자·시간)와 match_players 성적(Redis 마지막 상태)·전적을 한 트랜잭션으로 확정하고, 커밋 뒤 방 상태와 GAME_OVER를 처리한다.
 * matches는 status='PLAYING'일 때만 바뀌므로 topout·퇴장·끊김이 겹쳐도 한 번만 확정된다.
 * 시리즈가 남았거나 무승부·ABORTED면 GAME_OVER 5초 뒤 다음 라운드(또는 같은 라운드 재시도)를 시작한다(T-14).
 */
@Service
public class MatchOutcomeService {

    public enum Reason { TOP_OUT, DRAW, LEFT, DISCONNECT, ABORTED }

    /** BR-03 라운드 사이 5초: GAME_OVER 확정 5초 뒤 다음 라운드가 시작한다(COUNTDOWN은 그 3초 전에 보낸다) */
    static final long BETWEEN_ROUNDS_MS = 5_000;

    private final MatchRepository matches;
    private final RoomRepository roomRepository;
    private final UserRepository users;
    private final PlayerStateRepository states;
    private final RoomStateStore rooms;
    private final DisconnectTimerStore timers;
    private final RoomEventPublisher events;
    private final RoundStarter roundStarter;
    private final BattleScheduler scheduler;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final KeyPressTracker keyPresses;
    /** 방별 마지막 GAME_OVER payload — 끊김으로 시리즈가 끝날 때 다시 보낸다(단일 서버 기준) */
    private final Map<String, Map<String, Object>> lastGameOver = new ConcurrentHashMap<>();

    public MatchOutcomeService(MatchRepository matches, RoomRepository roomRepository, UserRepository users,
                               PlayerStateRepository states, RoomStateStore rooms, DisconnectTimerStore timers,
                               RoomEventPublisher events, RoundStarter roundStarter, BattleScheduler scheduler,
                               TransactionTemplate tx, Clock clock, KeyPressTracker keyPresses) {
        this.matches = matches;
        this.roomRepository = roomRepository;
        this.users = users;
        this.states = states;
        this.rooms = rooms;
        this.timers = timers;
        this.events = events;
        this.roundStarter = roundStarter;
        this.scheduler = scheduler;
        this.tx = tx;
        this.clock = clock;
        this.keyPresses = keyPresses;
    }

    static int winsNeeded(int roundCount) {
        return roundCount == 3 ? 2 : 1;
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
        // 시리즈 승자: 이번 승리로 필요한 승수에 닿거나, 시리즈 도중 나간 경우 남은 쪽(BR-03)
        int winnerWins = winnerId == null ? 0
                : Integer.parseInt(room.getOrDefault(RoomStateStore.WINS_FIELD_PREFIX + winnerId, "0")) + 1;
        Long seriesWinnerId = winnerId != null && (reason == Reason.LEFT || winnerWins >= winsNeeded(roundCount))
                ? winnerId : null;
        Instant now = clock.instant();
        int durationMs = (int) Math.max(0, Duration.between(match.startedAt(), now).toMillis());
        Map<Long, PlayerState> finalStates = new LinkedHashMap<>();
        match.slots().keySet().forEach(id -> finalStates.put(id, states.load(matchId, id)));
        // 봇 의심(T-41): 서버가 잰 라운드 시간 기준 초당 블록 수, lock과 함께 온 실제 키 입력 수로 판정한다(BotCheck 바로 차단 조건).
        // 한쪽이라도 의심이면 이번 라운드는 양쪽 전적에 넣지 않고 ranked = false로 남긴다(소명 검토용). 시리즈 진행은 그대로 둔다.
        List<Long> botSuspects = new ArrayList<>();
        finalStates.forEach((id, s) -> {
            if (BotCheck.judge(s.piecesPlaced(), durationMs, keyPresses.get(matchId, id), null).hard()) botSuspects.add(id);
        });
        keyPresses.clear(matchId, match.slots().keySet());
        boolean bot = !botSuspects.isEmpty();

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
            if (bot) matches.setRanked(matchId, false);
            if (!aborted && winnerId != null && !bot) { // 무승부·ABORTED·NONE·봇 의심은 전적을 바꾸지 않는다(BR-09, T-41)
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
        Long nextRoundStartsAt = null;
        if (seriesWinnerId != null) {
            finishSeries(code, match.roomId());
        } else {
            // 시리즈가 남았다: 승자가 있으면 다음 라운드, 무승부·ABORTED면 같은 라운드 재시도(attempt + 1)
            int seriesNo = Integer.parseInt(room.getOrDefault("seriesNo", "1"));
            int roundNo = Integer.parseInt(room.getOrDefault("roundNo", "1"));
            int attemptNo = Integer.parseInt(room.getOrDefault("attemptNo", "1"));
            int nextRound = winnerId != null ? roundNo + 1 : roundNo;
            int nextAttempt = winnerId != null ? 1 : attemptNo + 1;
            long startsAt = now.toEpochMilli() + BETWEEN_ROUNDS_MS;
            nextRoundStartsAt = startsAt;
            scheduler.after(Duration.ofMillis(BETWEEN_ROUNDS_MS - RoundStarter.COUNTDOWN_MS),
                    () -> startNextRound(code, match.roomId(), seriesNo, nextRound, nextAttempt, startsAt));
        }
        if (aborted) return true; // 양쪽 모두 끊겨 받을 사람이 없다(T-13 계획 unlinked)

        Map<String, Object> seriesWins = new LinkedHashMap<>();
        Map<String, String> after = rooms.getRoom(code);
        match.slots().keySet().forEach(id -> seriesWins.put(Long.toString(id),
                Integer.parseInt(after.getOrDefault(RoomStateStore.WINS_FIELD_PREFIX + id, "0"))));
        List<Map<String, Object>> stats = new ArrayList<>();
        finalStates.forEach((id, s) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", id);
            row.put("score", s.score());
            row.put("attackSent", s.attackSent());
            row.put("attackReceived", s.attackReceived());
            row.put("maxCombo", s.maxCombo());
            row.put("apm", Apm.of(s.attackSent(), durationMs));
            stats.add(row);
        });
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("matchId", matchId);
        payload.put("winnerUserId", winnerId);
        payload.put("reason", reason.name());
        payload.put("seriesWins", seriesWins);
        payload.put("seriesWinnerUserId", seriesWinnerId);
        payload.put("nextRoundStartsAt", nextRoundStartsAt);
        payload.put("stats", stats);
        payload.put("botSuspectedUserIds", botSuspects);
        // 다음 라운드 예약(startNextRound)만 다시 읽으므로 그때까지만 둔다. 시리즈가 끝났으면 남기지 않는다(보안 점검 L-8)
        if (nextRoundStartsAt != null) lastGameOver.put(code, payload);
        else lastGameOver.remove(code);
        events.publish(code, "GAME_OVER", payload);
        return true;
    }

    /**
     * 예약된 다음 라운드. 실행 때 방이 그 시리즈의 라운드 사이인지 다시 확인한다.
     * 한쪽이 끊긴 채 재접속하지 않았으면 남은 쪽이 시리즈 승자(POL-025), 둘 다 끊겼으면 시작하지 않는다.
     */
    void startNextRound(String code, long roomId, int seriesNo, int roundNo, int attemptNo, long startsAt) {
        Map<String, Object> last = lastGameOver.get(code);
        try {
            runNextRound(code, roomId, seriesNo, roundNo, attemptNo, startsAt, last);
        } finally {
            if (last != null) lastGameOver.remove(code, last); // 이 예약이 읽은 값만 지운다(보안 점검 L-8)
        }
    }

    private void runNextRound(String code, long roomId, int seriesNo, int roundNo, int attemptNo, long startsAt,
                              Map<String, Object> last) {
        Map<String, String> room = rooms.getRoom(code);
        String current = room.get("currentMatchId");
        if (!"PLAYING".equals(room.get("status")) || (current != null && !current.isBlank())
                || !Integer.toString(seriesNo).equals(room.get("seriesNo"))) return;
        Map<Long, RoomPlayer> players = rooms.getPlayers(code);
        if (players.size() != 2) return;
        List<Long> connected = players.entrySet().stream().filter(e -> e.getValue().connected()).map(Map.Entry::getKey).toList();
        if (connected.isEmpty()) return; // 받을 사람이 없다 — 방은 비활성 만료로 닫힌다(POL-024)
        if (connected.size() == 1) {
            long remaining = connected.get(0);
            if (decideSeriesByAbsence(code, roomId, seriesNo, remaining)) {
                if (last != null) {
                    Map<String, Object> again = new LinkedHashMap<>(last);
                    again.put("seriesWinnerUserId", remaining);
                    again.put("nextRoundStartsAt", null);
                    events.publish(code, "GAME_OVER", again);
                }
            }
            return;
        }
        roundStarter.start(code, seriesNo, roundNo, attemptNo, startsAt);
    }

    /** 라운드 사이 퇴장(BR-03): 남은 쪽이 시리즈 승자 */
    public void onLeaveBetweenRounds(String code, long leaverId) {
        Map<String, String> room = rooms.getRoom(code);
        if (room.get("roomId") == null) return;
        int seriesNo = Integer.parseInt(room.getOrDefault("seriesNo", "1"));
        rooms.getPlayers(code).keySet().stream().filter(id -> id != leaverId).findFirst()
                .ifPresent(remaining -> decideSeriesByAbsence(code, Long.parseLong(room.get("roomId")), seriesNo, remaining));
    }

    /** 진행 중 경기 없이 시리즈를 끝낸다: 마지막 경기 행에 시리즈 승자를 적고 방을 FINISHED로. 이미 끝났으면 false */
    private boolean decideSeriesByAbsence(String code, long roomId, int seriesNo, long winnerId) {
        if (!rooms.compareAndSetStatus(code, "PLAYING", "FINISHED")) return false;
        matches.findLastSeriesMatch(roomId, seriesNo).ifPresent(id -> matches.setSeriesWinner(id, winnerId));
        roomRepository.updateStatus(roomId, "FINISHED");
        return true;
    }

    private void finishSeries(String code, long roomId) {
        if (rooms.compareAndSetStatus(code, "PLAYING", "FINISHED")) {
            roomRepository.updateStatus(roomId, "FINISHED");
        }
    }
}
