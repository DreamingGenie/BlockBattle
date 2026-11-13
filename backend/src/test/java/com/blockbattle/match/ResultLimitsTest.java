package com.blockbattle.match;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * BR-07 상한 네 가지의 경계값. 깨지면: 정상 기록이 랭킹에서 빠지거나 상한을 넘은 기록이 랭킹에 들어간다.
 * 기준 기록: 줄 62, 레벨 7, 블록 171, 최대 콤보 5, 184,000ms → 점수 상한 7 × (62 × 1,450 + 171 × 400) + 40 × 171 = 1,114,940
 */
class ResultLimitsTest {

    static boolean check(long score, long lines, long level, long pieces, long received, long combo, long duration) {
        return ResultLimits.withinLimits(score, lines, level, pieces, received, combo, duration);
    }

    @Test
    void 기준_기록과_점수_상한_경계() {
        assertThat(check(45_200, 62, 7, 171, 0, 5, 184_000)).isTrue();
        assertThat(check(1_114_940, 62, 7, 171, 0, 5, 184_000)).isTrue();
        assertThat(check(1_114_941, 62, 7, 171, 0, 5, 184_000)).isFalse();
    }

    @Test
    void 레벨은_1_더하기_줄_나누기_10의_몫이어야_한다() {
        assertThat(check(0, 9, 1, 30, 0, 0, 10_000)).isTrue();
        assertThat(check(0, 10, 2, 30, 0, 0, 10_000)).isTrue();
        assertThat(check(0, 10, 1, 30, 0, 0, 10_000)).isFalse();
        assertThat(check(0, 62, 8, 171, 0, 5, 184_000)).isFalse();
    }

    @Test
    void 지운_줄은_블록_4칸과_받은_공격_9칸으로_채울_수_있는_만큼까지() {
        // (171 × 4) ÷ 10 = 68
        assertThat(check(0, 68, 7, 171, 0, 0, 184_000)).isTrue();
        assertThat(check(0, 69, 7, 171, 0, 0, 184_000)).isFalse();
        // 받은 공격 2줄: (171 × 4 + 18) ÷ 10 = 70
        assertThat(check(0, 70, 8, 171, 2, 0, 184_000)).isTrue();
        assertThat(check(0, 71, 8, 171, 2, 0, 184_000)).isFalse();
    }

    @Test
    void 놓은_블록은_플레이_시간_100ms당_1개까지() {
        assertThat(check(0, 0, 1, 1_840, 0, 0, 184_000)).isTrue();
        assertThat(check(0, 0, 1, 1_841, 0, 0, 184_000)).isFalse();
        assertThat(check(0, 0, 1, 1, 0, 0, 99)).isFalse();
    }
}
