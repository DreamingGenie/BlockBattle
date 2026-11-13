package com.blockbattle.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 블록 하나를 고정한 결과(GR-05~GR-08). FE 엔진 resolveLock과 같은 입력에 같은 결과를 낸다(CON-002, 공용 lock-cases.json).
 * 친구 대전 서버 재계산(BR-05)에 쓴다. 드롭 점수는 lock 메시지에 드롭 칸 수가 없어 계산하지 않는다.
 */
public final class LockRules {

    /** lock 직전 상태. combo는 직전 연속 삭제 수(끊긴 상태 = -1), b2b는 직전 줄 삭제가 어려운 삭제였는지 */
    public record State(Board board, int linesTotal, int combo, boolean b2b, List<GarbageBundle> pending) {
    }

    public record Result(Board board, int linesCleared, boolean tSpin, boolean perfectClear, boolean backToBack,
                         int score, int level, int attack, int cancelled, int sent, int garbageRaised, State next) {
    }

    private static final int[] LINE_SCORE = {0, 100, 300, 500, 800};
    private static final int[] TSPIN_SCORE = {400, 800, 1200, 1600};
    private static final int[] LINE_ATTACK = {0, 0, 1, 2, 4};
    private static final int[] TSPIN_ATTACK = {0, 2, 4, 6};
    private static final int PERFECT_CLEAR_ATTACK = 10;

    private LockRules() {
    }

    public static int levelForLines(int linesTotal) {
        return 1 + linesTotal / 10;
    }

    /** GR-05: T, 마지막 성공 동작이 회전, 중심 기준 대각선 네 칸 중 3칸 이상 막힘(벽·바닥 포함). board는 놓기 전 */
    public static boolean isTSpin(Board board, Placement p, boolean lastActionRotate) {
        if (p.piece() != Piece.T || !lastActionRotate) return false;
        int blocked = 0;
        int[][] corners = {{p.y(), p.x()}, {p.y(), p.x() + 2}, {p.y() + 2, p.x()}, {p.y() + 2, p.x() + 2}};
        for (int[] rc : corners) if (board.isBlocked(rc[0], rc[1])) blocked++;
        return blocked >= 3;
    }

    static int comboAttack(int combo) {
        return combo <= 0 ? 0 : Math.min(4, (combo + 1) / 2);
    }

    public static Result resolve(State state, Placement placement, boolean lastActionRotate) {
        boolean tSpin = isTSpin(state.board(), placement, lastActionRotate);
        Board.Cleared cleared = state.board().place(placement).clearLines();
        int lines = cleared.lines();
        int level = levelForLines(state.linesTotal());

        boolean difficult = lines > 0 && (lines == 4 || tSpin);
        boolean backToBack = difficult && state.b2b();
        int combo = lines > 0 ? state.combo() + 1 : -1;
        boolean b2b = lines > 0 ? difficult : state.b2b();
        boolean perfectClear = lines > 0 && cleared.board().isEmpty();

        double base = (tSpin ? TSPIN_SCORE[lines] : LINE_SCORE[lines]) * (double) level;
        if (backToBack) base *= 1.5;
        double comboScore = combo > 0 ? 50.0 * combo * level : 0;
        int score = (int) Math.floor(base + comboScore);

        int attack = 0;
        if (lines > 0) {
            attack = (tSpin ? TSPIN_ATTACK[lines] : LINE_ATTACK[lines]) + (backToBack ? 1 : 0) + comboAttack(combo);
            if (perfectClear) attack += PERFECT_CLEAR_ATTACK;
        }

        // 보낼 줄 수만큼 먼저 받은 묶음부터 상쇄한다
        int left = attack;
        List<GarbageBundle> pending = new ArrayList<>();
        for (GarbageBundle b : state.pending()) {
            if (left >= b.lines()) left -= b.lines();
            else {
                pending.add(new GarbageBundle(b.lines() - left, b.holeColumn()));
                left = 0;
            }
        }
        int cancelled = attack - left;
        int sent = attack - cancelled;

        Board board = cleared.board();
        int garbageRaised = 0;
        if (lines == 0 && !pending.isEmpty()) {
            board = board.raiseGarbage(pending);
            garbageRaised = pending.stream().mapToInt(GarbageBundle::lines).sum();
            pending = List.of();
        }
        State next = new State(board, state.linesTotal() + lines, combo, b2b, List.copyOf(pending));
        return new Result(board, lines, tSpin, perfectClear, backToBack, score, level, attack, cancelled, sent,
                garbageRaised, next);
    }
}
