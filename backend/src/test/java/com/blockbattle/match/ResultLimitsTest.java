package com.blockbattle.match;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * BR-07 상한 네 가지의 경계값. 깨지면: 정상 기록이 랭킹에서 빠지거나 상한을 넘은 기록이 랭킹에 들어간다.
 * 기준 기록: 줄 62, 레벨 7, 블록 171, 최대 콤보 5, 184,000ms → 점수 상한 7 × (62 × 1,450 + 171 × 400) + 40 × 171 = 1,114,940
 */
class ResultLimitsTest {

    /** ①~④·⑥ 시험용: 서버 경과 시간은 플레이 시간보다 넉넉하게 둔다 */
    static boolean check(long score, long lines, long level, long pieces, long received, long combo, long duration) {
        return ResultLimits.withinLimits(score, lines, level, pieces, received, combo, duration, duration + 60_000);
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

    /** ⑤ 깨지면: 시작 직후에 긴 플레이 시간을 신고해 ③을 우회하는 위조가 다시 랭킹에 들어간다(보안 점검 H-1) */
    @Test
    void 플레이_시간은_서버_경과_시간_더하기_2초까지() {
        assertThat(ResultLimits.withinLimits(45_200, 62, 7, 171, 0, 5, 184_000, 182_000)).isTrue();
        assertThat(ResultLimits.withinLimits(45_200, 62, 7, 171, 0, 5, 184_001, 182_000)).isFalse();
    }

    /** ⑥ 깨지면: 최대 콤보를 부풀려 ④ 점수 상한을 무한히 키울 수 있다(보안 점검 H-1) */
    @Test
    void 최대_콤보는_놓은_블록_수까지() {
        assertThat(check(0, 0, 1, 171, 0, 171, 184_000)).isTrue();
        assertThat(check(0, 0, 1, 171, 0, 172, 184_000)).isFalse();
    }

    /** 보안 점검 보고서 H-1의 위조 예시: 이전에는 ④ 상한이 약 10¹⁰이 되어 통과했다 */
    @Test
    void 보고서의_위조_예시는_상한을_넘는다() {
        assertThat(ResultLimits.withinLimits(2_147_483_647L, 4, 1, 10, 0, 50_000_000, 1_000, 1_000)).isFalse();
    }
}
