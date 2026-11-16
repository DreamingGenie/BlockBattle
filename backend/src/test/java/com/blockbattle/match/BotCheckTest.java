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

    @Test
    void 블록이_적거나_값이_없으면_판정하지_않는다() {
        assertThat(BotCheck.judge(12, 1_000, 0, null).bot(true)).as("손을 놓고 있다 천장에 닿은 판").isFalse();
        assertThat(BotCheck.judge(60, 120_000, null, null).bot(true)).as("옛 클라이언트").isFalse();
    }
}
