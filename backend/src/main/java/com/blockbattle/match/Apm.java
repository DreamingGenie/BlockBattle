package com.blockbattle.match;

/** POL-012: APM은 저장하지 않고 attack_sent ÷ 플레이 분으로 계산해 소수 첫째 자리까지 표시한다 */
public final class Apm {

    private Apm() {
    }

    public static double of(int attackSent, Integer durationMs) {
        if (durationMs == null || durationMs <= 0) return 0;
        return Math.round(attackSent / (durationMs / 60_000.0) * 10) / 10.0;
    }
}
