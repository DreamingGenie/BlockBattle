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
}
