package com.blockbattle.battle;

import com.blockbattle.engine.Board;
import com.blockbattle.engine.GarbageBundle;
import com.blockbattle.engine.LockRules;
import com.blockbattle.engine.Piece;
import com.blockbattle.engine.PieceSequence;
import com.blockbattle.engine.Placement;
import com.blockbattle.realtime.RoomEventPublisher;
import com.blockbattle.store.redis.MatchStateStore;
import com.blockbattle.store.redis.RoomStateStore;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * 친구 대전 lock 검증·재계산(BR-05, POL-005, REQ-012).
 * ① seq = 직전 + 1(같으면 무시, 건너뛰면 INVALID_LOCK) ② 시드 순서·홀드로 나올 수 있는 블록 ③ 서버 보드에서 겹치지 않고 바로 아래가 막힘.
 * 서버가 GR-05~GR-08로 다시 계산하고, 어긋나거나 지운 줄 수가 다르면 INVALID_LOCK과 서버 보드를 보낸다. 경로는 검사하지 않는다.
 */
@Service
public class LockService {

    /**
     * API 명세 5.2. softDropCells·hardDropCells는 Object로 받아 문자열·소수가 와도 lock 전체가 버려지지 않게 한다
     * (정수가 아니면 0, POL-005).
     */
    public record LockMessage(Integer seq, String piece, Integer rotation, Integer x, Integer y, Boolean holdUsed,
                              String lastMove, Integer linesCleared, Object softDropCells, Object hardDropCells,
                              Integer keyPresses) {
    }

    /** 새 블록이 나오는 행(GR-04, 회전 0·x 3·y 0) */
    static final int SPAWN_Y = 0;

    private final RoomStateStore rooms;
    private final PlayerStateRepository states;
    private final RoomEventPublisher events;
    private final SimpMessagingTemplate messaging;
    private final JdbcTemplate jdbc;
    private final MatchOutcomeService outcomes;
    private final BattleScheduler scheduler;
    private final Clock clock;
    private final KeyPressTracker keyPresses;
    private final MatchStateStore matchState;
    private final SecureRandom random = new SecureRandom();
    /**
     * 경기별 순서 잠금. 경기마다 객체를 만들어 맵에 두면 끝난 경기가 쌓이므로(보안 점검 L-8) 고정 개수로 나눠 쓴다.
     * 다른 경기가 같은 칸을 쓰면 잠깐 기다릴 뿐 결과는 같다.
     */
    static final int LOCK_STRIPES = 1024;
    private final Object[] matchLocks = new Object[LOCK_STRIPES];
    /** 시드 캐시. 끝난 경기가 쌓이지 않게 최근 SEED_CACHE_SIZE개만 두고, 빠진 값은 DB에서 다시 읽는다(L-8) */
    static final int SEED_CACHE_SIZE = 1024;
    private final Map<Long, Long> seeds = Collections.synchronizedMap(new LinkedHashMap<Long, Long>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
            return size() > SEED_CACHE_SIZE;
        }
    });

    public LockService(RoomStateStore rooms, PlayerStateRepository states, RoomEventPublisher events,
                       SimpMessagingTemplate messaging, JdbcTemplate jdbc, MatchOutcomeService outcomes,
                       BattleScheduler scheduler, Clock clock, KeyPressTracker keyPresses, MatchStateStore matchState) {
        this.rooms = rooms;
        this.states = states;
        this.events = events;
        this.messaging = messaging;
        this.jdbc = jdbc;
        this.outcomes = outcomes;
        this.scheduler = scheduler;
        this.clock = clock;
        this.keyPresses = keyPresses;
        this.matchState = matchState;
        Arrays.setAll(matchLocks, i -> new Object());
    }

    private Object lockOf(long matchId) {
        return matchLocks[Math.floorMod(Long.hashCode(matchId), LOCK_STRIPES)];
    }

    /** BR-04 동시 topout 판정 창 */
    static final Duration DRAW_WINDOW = Duration.ofMillis(100);

    /**
     * API 명세 5.3 topout. 클라이언트 신고는 보조 신호다 — 다음 블록이 막힌 lock은 handle()이 서버 보드로 직접 판정한다.
     * seq가 마지막 lock과 같고 서버 보드에서 나올 블록(홀드로 나온 블록 포함)이 생성 자리(회전 0, x 3, y 0)에 들어가지 않으면
     * topoutAt을 남기고 100ms 뒤 상대 topout을 보고 TOP_OUT·DRAW를 정한다(BR-04).
     * 서버 보드에서는 아직 나올 수 있으면 INVALID_LOCK과 서버 보드를 보내 클라이언트가 RESYNC로 맞춰 이어 하게 한다.
     * 그냥 버리면 클라이언트는 멈춘 채 경기가 끝나지 않아, 늦게 topout한 상대가 지게 된다.
     * seq가 다르면 무시한다(옛 신고이거나, 거부된 lock 뒤의 신고라 그 클라이언트는 이미 RESYNC로 맞추는 중이다).
     */
    public void topout(String code, long userId, Integer seq, Boolean holdUsed) {
        Map<String, String> room = rooms.getRoom(code);
        String current = room.get("currentMatchId");
        if (seq == null || !"PLAYING".equals(room.get("status")) || current == null || current.isBlank()) return;
        long matchId = Long.parseLong(current);
        synchronized (lockOf(matchId)) {
            PlayerState me = states.load(matchId, userId);
            if (me.topoutAt() != null || seq != me.seq()) return;
            if (canSpawn(matchId, me, Boolean.TRUE.equals(holdUsed))) {
                rejectLock(userId, seq, me);
                return;
            }
            states.save(matchId, userId, withTopout(me));
        }
        scheduler.after(DRAW_WINDOW, () -> decideTopout(code, matchId, userId));
    }

    /** 다음에 나올 블록이 서버 보드의 생성 자리에 들어가는지. 홀드로 나오는 블록은 홀드 칸이 비었으면 그다음 블록이다 */
    private boolean canSpawn(long matchId, PlayerState me, boolean viaHold) {
        PieceSequence sequence = new PieceSequence(seedOf(matchId));
        Piece next = !viaHold ? sequence.at(me.pieceIndex())
                : me.hold() != null ? me.hold() : sequence.at(me.pieceIndex() + 1);
        return me.board().fits(new Placement(next, 0, 3, SPAWN_Y));
    }

    private PlayerState withTopout(PlayerState s) {
        return new PlayerState(s.board(), s.seq(), s.pieceIndex(), s.hold(), s.holdUsed(), s.pending(), s.combo(), s.b2b(),
                s.score(), s.linesCleared(), s.level(), s.piecesPlaced(), s.attackSent(), s.attackReceived(), s.maxCombo(),
                clock.millis());
    }

    /**
     * topout 100ms 뒤(BR-04): 나와, 내 topout 뒤 100ms 안에 topout한 사람을 함께 탈락시킨다(같은 등수).
     * 나보다 먼저 topout한 사람은 이미 자기 판정에서 탈락했다. 2인이면 예전처럼 먼저 낸 쪽 패배, 100ms 안이면 무승부다.
     */
    private void decideTopout(String code, long matchId, long userId) {
        Long mine = states.load(matchId, userId).topoutAt();
        if (mine == null) return;
        List<Long> group = new ArrayList<>(List.of(userId));
        for (long id : rooms.getPlayers(code).keySet()) {
            if (id == userId) continue;
            Long at = states.load(matchId, id).topoutAt();
            if (at != null && at <= mine + DRAW_WINDOW.toMillis()) group.add(id);
        }
        outcomes.eliminate(code, matchId, group, MatchOutcomeService.Reason.TOP_OUT);
    }

    public void handle(String code, long userId, LockMessage msg) {
        Map<String, String> room = rooms.getRoom(code);
        String current = room.get("currentMatchId");
        if (!"PLAYING".equals(room.get("status")) || current == null || current.isBlank()) return;
        long matchId = Long.parseLong(current);
        List<Long> others = rooms.getPlayers(code).keySet().stream().filter(id -> id != userId).toList();

        // 같은 경기의 lock과 공격 반영은 순서대로(단일 서버 기준, T-12 계획 unlinked)
        boolean toppedOut;
        synchronized (lockOf(matchId)) {
            PlayerState me = states.load(matchId, userId);
            if (msg == null || msg.seq() == null) return;
            if (msg.seq() == me.seq() || me.topoutAt() != null) return; // 같은 seq·topout 뒤 lock은 무시
            Outcome outcome = evaluate(matchId, me, msg);
            if (outcome == null) {
                rejectLock(userId, msg.seq(), me);
                return;
            }
            // 이 lock 뒤 다음 블록이 서버 보드에서 막히면 서버가 바로 topout으로 본다. 클라이언트의 topout 신고가
            // lock보다 먼저 처리되거나 사라져도 경기가 끝나지 않는 일이 없게 한다(BR-04)
            toppedOut = !canSpawn(matchId, outcome.state(), false);
            states.save(matchId, userId, toppedOut ? withTopout(outcome.state()) : outcome.state());
            keyPresses.record(matchId, userId, msg.keyPresses()); // 봇 판정용(T-41, 선택 필드)
            rooms.touch(code); // lock은 활동이다(POL-024)

            Map<String, Object> board = new LinkedHashMap<>();
            board.put("userId", userId);
            board.put("seq", outcome.state().seq());
            board.put("board", outcome.state().board().toString());
            board.put("score", outcome.state().score());
            board.put("linesCleared", outcome.state().linesCleared());
            board.put("level", outcome.state().level());
            board.put("combo", outcome.state().combo());
            board.put("b2b", outcome.state().b2b());
            board.put("pendingGarbage", outcome.state().pendingLines());
            events.publish(code, "BOARD", board);

            int sent = outcome.result().sent();
            // 공격은 아직 남은 상대 중 무작위 한 명에게 간다(다인 대전 2026-11-19, 2인이면 늘 그 상대)
            Map<Long, Integer> out = sent > 0 ? matchState.getPlacements(matchId, others) : Map.of();
            Map<Long, PlayerState> targets = new LinkedHashMap<>();
            if (sent > 0) {
                for (long id : others) {
                    if (out.containsKey(id)) continue;
                    PlayerState s = states.load(matchId, id);
                    if (s.topoutAt() == null) targets.put(id, s);
                }
            }
            if (!targets.isEmpty()) {
                long opponentId = new ArrayList<>(targets.keySet()).get(random.nextInt(targets.size()));
                int hole = random.nextInt(Board.WIDTH); // 묶음마다 구멍 열을 서버가 무작위로(GR-08)
                PlayerState opp = targets.get(opponentId);
                List<GarbageBundle> pending = new ArrayList<>(opp.pending());
                pending.add(new GarbageBundle(sent, hole));
                states.save(matchId, opponentId, new PlayerState(opp.board(), opp.seq(), opp.pieceIndex(), opp.hold(),
                        opp.holdUsed(), List.copyOf(pending), opp.combo(), opp.b2b(), opp.score(), opp.linesCleared(),
                        opp.level(), opp.piecesPlaced(), opp.attackSent(), opp.attackReceived() + sent, opp.maxCombo(),
                        opp.topoutAt()));
                events.publish(code, "ATTACK", Map.of("fromUserId", userId, "toUserId", opponentId, "lines", sent, "holeColumn", hole));
            }
        }
        if (toppedOut) scheduler.after(DRAW_WINDOW, () -> decideTopout(code, matchId, userId));
    }

    private record Outcome(PlayerState state, LockRules.Result result) {
    }

    /** 검사를 모두 통과하면 다음 상태, 하나라도 어긋나면 null */
    private Outcome evaluate(long matchId, PlayerState me, LockMessage msg) {
        if (msg.seq() != me.seq() + 1) return null;
        Piece piece;
        int rotation;
        try {
            piece = Piece.valueOf(msg.piece());
            rotation = msg.rotation();
        } catch (RuntimeException e) {
            return null;
        }
        if (rotation < 0 || rotation > 3 || msg.x() == null || msg.y() == null || msg.linesCleared() == null) return null;

        // ② 시드 순서·홀드 상태로 나올 수 있는 블록인지
        PieceSequence sequence = new PieceSequence(seedOf(matchId));
        boolean usedHold = Boolean.TRUE.equals(msg.holdUsed());
        Piece expected;
        Piece nextHold;
        int nextIndex;
        if (!usedHold) {
            expected = sequence.at(me.pieceIndex());
            nextHold = me.hold();
            nextIndex = me.pieceIndex() + 1;
        } else if (me.hold() == null) {
            expected = sequence.at(me.pieceIndex() + 1);
            nextHold = sequence.at(me.pieceIndex());
            nextIndex = me.pieceIndex() + 2;
        } else {
            expected = me.hold();
            nextHold = sequence.at(me.pieceIndex());
            nextIndex = me.pieceIndex() + 1;
        }
        if (piece != expected) return null;

        // ③ 서버 보드에서 겹치지 않고 바로 아래가 막혔는지
        Placement placement = new Placement(piece, rotation, msg.x(), msg.y());
        if (!me.board().fits(placement) || !me.board().isGrounded(placement)) return null;

        LockRules.Result r = LockRules.resolve(
                new LockRules.State(me.board(), me.linesCleared(), me.combo(), me.b2b(), me.pending()),
                placement, "ROTATE".equals(msg.lastMove()));
        if (r.linesCleared() != msg.linesCleared()) return null;

        LockRules.State n = r.next();
        int dropScore = dropScore(msg.y(), msg.softDropCells(), msg.hardDropCells());
        PlayerState next = new PlayerState(n.board(), msg.seq(), nextIndex, nextHold, false, n.pending(), n.combo(), n.b2b(),
                me.score() + r.score() + dropScore, n.linesTotal(), LockRules.levelForLines(n.linesTotal()), me.piecesPlaced() + 1,
                me.attackSent() + r.sent(), me.attackReceived(), Math.max(me.maxCombo(), n.combo()), me.topoutAt());
        return new Outcome(next, r);
    }

    /**
     * GR-07 ④ 드롭 점수(소프트 칸당 1, 하드 칸당 2). 경로는 검사하지 않으므로(POL-005) 두 칸 수의 합을
     * 생성 행에서 내려온 행 수(y − SPAWN_Y)로 묶고, 넘으면 하드 칸부터 잘라낸다. 정수가 아니거나 음수면 0이다.
     */
    static int dropScore(int y, Object softValue, Object hardValue) {
        int cap = Math.max(0, y - SPAWN_Y);
        int soft = dropCells(softValue);
        int hard = dropCells(hardValue);
        if (soft + hard > cap) hard = Math.max(0, cap - soft);
        if (soft > cap) soft = cap;
        return soft + 2 * hard;
    }

    private static int dropCells(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            long cells = ((Number) value).longValue();
            return cells < 0 ? 0 : (int) Math.min(cells, Board.HEIGHT);
        }
        return 0;
    }

    /** API 명세 5.6: 보낸 사람에게만. 클라이언트는 서버 보드로 바꾸고 nextSeq부터 다시 보낸다 */
    private void rejectLock(long userId, int seq, PlayerState me) {
        messaging.convertAndSendToUser(Long.toString(userId), "/queue/errors", Map.of(
                "code", "INVALID_LOCK",
                "message", "서버 보드와 맞지 않습니다.",
                "payload", Map.of("seq", seq, "board", me.board().toString(), "nextSeq", me.seq() + 1)));
    }

    /** 경기 시드(matches.seed). RESYNC(POL-006)도 이 값을 쓴다 */
    long seedOf(long matchId) {
        Long seed = seeds.get(matchId);
        if (seed == null) { // DB 조회는 캐시 잠금 밖에서 한다(같은 값을 두 번 읽어도 무해)
            seed = jdbc.queryForObject("SELECT seed FROM matches WHERE id = ?", Long.class, matchId);
            seeds.put(matchId, seed);
        }
        return seed;
    }
}
