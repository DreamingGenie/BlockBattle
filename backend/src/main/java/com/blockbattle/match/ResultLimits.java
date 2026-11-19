package com.blockbattle.match;

/**
 * 싱글·AI 점수 상한 검사(BR-07, POL-007). 하나라도 어기면 결과는 저장하되 ranked = false.
 * ① 레벨 = 1 + (지운 줄 ÷ 10의 몫)
 * ② 지운 줄 ≤ (놓은 블록 × 4 + 받은 공격 줄 × 9) ÷ 10의 몫 (싱글은 받은 공격 줄 0)
 * ③ 놓은 블록 ≤ 플레이 시간(ms) ÷ 100
 * ④ 점수 ≤ 레벨 × (지운 줄 × (1,200 + 50 × 최대 콤보) + 놓은 블록 × 400) + 40 × 놓은 블록
 * ⑤ 플레이 시간 ≤ 서버 경과 시간(결과 저장 시각 − 경기 시작 시각) + 2,000ms (T-30, 보안 점검 H-1)
 *    ③이 클라이언트가 보낸 플레이 시간을 그대로 믿지 않게 한다. 일시정지 시간은 클라이언트 플레이 시간에서 빠지므로
 *    정상 경기는 항상 통과한다.
 * ⑥ 최대 콤보 ≤ 놓은 블록 (T-30) — 콤보는 블록을 놓을 때만 이어지므로 놓은 블록 수를 넘을 수 없다. ④의 상한을 묶는다.
 */
public final class ResultLimits {

    /** ⑤의 여유: 시작·결과 요청의 왕복 시간과 클라이언트 측정 오차 */
    static final long CLOCK_SLACK_MS = 2_000;

    private ResultLimits() {
    }

    public static boolean withinLimits(long score, long lines, long level, long pieces, long attackReceived, long maxCombo,
                                       long durationMs, long serverElapsedMs) {
        if (level != 1 + lines / 10) return false;
        if (lines > (pieces * 4 + attackReceived * 9) / 10) return false;
        if (pieces > durationMs / 100) return false;
        if (durationMs > serverElapsedMs + CLOCK_SLACK_MS) return false;
        if (maxCombo > pieces) return false;
        return score <= level * (lines * (1200 + 50 * maxCombo) + pieces * 400) + 40 * pieces;
    }

    /**
     * ⑦ AI 블록 수는 서버 경과 시간 안에 둘 수 있어야 한다. AI는 입력 하나에 60ms 이상(IMPOSSIBLE)이고 블록마다 입력이 하나 이상이라
     * 블록당 50ms로 넉넉히 잡는다. 플레이 시간(클라이언트 값)이 아니라 서버 경과 시간으로 잰다.
     */
    static final long AI_MIN_MS_PER_PIECE = 50;

    /**
     * ⑧ 승리라면 AI가 topout할 만큼 쌓였어야 한다. 블록 하나는 가장 높은 칸을 최대 4칸, 받은 공격 줄은 1칸 올리므로
     * 생성 자리(숨김 2행)에 닿으려면 4 × AI 블록 + AI가 받은 공격 줄 ≥ 20이어야 한다(위 2행을 빼고 넉넉히).
     */
    static final long AI_TOPOUT_MIN_HEIGHT = 20;

    /** vs AI 결과의 AI 쪽 검사(보안 점검 L-6). 어기면 ranked = false이고 AI 전적에도 넣지 않는다 */
    public static boolean aiResultPlausible(boolean win, long aiPieces, long aiAttackReceived, long serverElapsedMs) {
        if (aiPieces * AI_MIN_MS_PER_PIECE > serverElapsedMs) return false;
        return !win || aiPieces * 4 + aiAttackReceived >= AI_TOPOUT_MIN_HEIGHT;
    }
}
