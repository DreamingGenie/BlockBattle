package com.blockbattle.match;

/**
 * 싱글·AI 점수 상한 검사(BR-07, POL-007). 하나라도 어기면 결과는 저장하되 ranked = false.
 * ① 레벨 = 1 + (지운 줄 ÷ 10의 몫)
 * ② 지운 줄 ≤ (놓은 블록 × 4 + 받은 공격 줄 × 9) ÷ 10의 몫 (싱글은 받은 공격 줄 0)
 * ③ 놓은 블록 ≤ 플레이 시간(ms) ÷ 100
 * ④ 점수 ≤ 레벨 × (지운 줄 × (1,200 + 50 × 최대 콤보) + 놓은 블록 × 400) + 40 × 놓은 블록
 */
public final class ResultLimits {

    private ResultLimits() {
    }

    public static boolean withinLimits(long score, long lines, long level, long pieces, long attackReceived, long maxCombo,
                                       long durationMs) {
        if (level != 1 + lines / 10) return false;
        if (lines > (pieces * 4 + attackReceived * 9) / 10) return false;
        if (pieces > durationMs / 100) return false;
        return score <= level * (lines * (1200 + 50 * maxCombo) + pieces * 400) + 40 * pieces;
    }
}
