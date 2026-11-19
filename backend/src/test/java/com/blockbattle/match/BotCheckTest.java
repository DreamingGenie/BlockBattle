package com.blockbattle.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.blockbattle.match.BotCheck.InputStats;
import org.junit.jupiter.api.Test;

/**
 * T-39·T-40 봇 판정. 깨지면 콘솔 스크립트 봇이 랭킹에 오르거나, 빠르고 정확한 진짜 고수·옛 클라이언트가 봇으로 몰린다.
 */
class BotCheckTest {

    static final InputStats HUMAN = new InputStats(0.55, 95.0, 0.4, 3.2);

    @Test
    void 사람이_낼_수_없는_속도는_바로_봇이다() {
        assertThat(BotCheck.judge(2048, 326_718, null, null).hard()).as("실제 봇 기록: 초당 6.3개가 아닌 7 초과만").isFalse();
        assertThat(BotCheck.judge(358, 17_812, null, null).hard()).as("실제 봇 기록: 초당 20개").isTrue();
    }

    @Test
    void 키_입력이_놓은_블록보다_적으면_바로_봇이다() {
        assertThat(BotCheck.judge(60, 120_000, 59, HUMAN).hard()).isTrue();
        assertThat(BotCheck.judge(60, 120_000, 60, HUMAN).hard()).isFalse();
    }

    @Test
    void 빠르고_정확한_고수는_신호_하나라_봇이_아니다() {
        // 초당 5개(신호 1)지만 간격·키 누름이 사람답다
        BotCheck.Verdict v = BotCheck.judge(500, 100_000, 1600, HUMAN);
        assertThat(v.hard()).isFalse();
        assertThat(v.softSignals()).isEqualTo(1);
        assertThat(v.bot(true)).isFalse();
    }

    @Test
    void 일정한_간격과_기계적인_키_입력이_겹치면_점수제로_봇_쪽이다() {
        BotCheck.Verdict v = BotCheck.judge(200, 100_000, 300, new InputStats(0.05, 10.0, 0.02, 1.5));
        assertThat(v.softSignals()).isEqualTo(2);
        assertThat(v.bot(true)).isTrue();
        assertThat(v.bot(false)).as("기준값을 정하기 전(관찰 모드)에는 기록만 한다").isFalse();
    }

    /**
     * 2026-11-19 운영 데이터로 다시 정한 기준. 깨지면: IMPOSSIBLE을 이긴 봇(경기 2188, 초당 6.55·키 누름 평균 18ms)이 다시 통과하거나,
     * 입력 요약값을 빼고 직접 보낸 빠른 결과가 통과한다.
     */
    @Test
    void IMPOSSIBLE을_이긴_봇의_실제_입력은_점수제로_봇이다() {
        BotCheck.Verdict v = BotCheck.judge(128, 19_536, 619, new InputStats(1.491, 18.0, 5.262, 4.84));
        assertThat(v.hard()).as("초당 7 아래라 바로 차단은 아니다").isFalse();
        assertThat(v.softSignals()).as("초당 4 초과 + 키 누름 평균 50ms 미만").isEqualTo(2);
        assertThat(v.bot(true)).isTrue();

        BotCheck.Verdict noStats = BotCheck.judge(2048, 326_718, null, null);
        assertThat(noStats.softSignals()).as("초당 6.3 + 입력 값 없음").isEqualTo(2);
        assertThat(noStats.bot(true)).isTrue();
    }

    @Test
    void 사람의_키_누름_분포는_걸리지_않는다() {
        // 사용자별 키 누름 평균 중앙값은 대부분 77~259ms. 가장 짧은 사람(26~31ms)도 초당 블록이 2.5 아래라 신호 하나다
        assertThat(BotCheck.judge(300, 120_000, 1200, new InputStats(0.8, 77.0, 1.2, 4.0)).softSignals()).isZero();
        assertThat(BotCheck.judge(300, 120_000, 1400, new InputStats(1.0, 26.0, 2.3, 4.5)).bot(true)).isFalse();
    }

    @Test
    void 블록이_적거나_값이_없으면_판정하지_않는다() {
        assertThat(BotCheck.judge(12, 1_000, 0, null).bot(true)).as("손을 놓고 있다 천장에 닿은 판").isFalse();
        assertThat(BotCheck.judge(60, 120_000, null, null).bot(true)).as("옛 클라이언트").isFalse();
    }
}
