package com.blockbattle.match;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** T-39 봇 의심 판정. 깨지면 콘솔 스크립트 봇이 랭킹에 오르거나, 정상 플레이어·옛 클라이언트가 봇으로 몰린다 */
class BotCheckTest {

    @Test
    void 초당_블록_4개를_넘으면_봇이다() {
        assertThat(BotCheck.suspect(284, 39_537, null)).isTrue();   // 실제 봇 기록: 초당 7.2개
        assertThat(BotCheck.suspect(171, 184_000, null)).isFalse(); // 초당 0.9개
    }

    @Test
    void 키_입력이_놓은_블록보다_적으면_봇이다() {
        assertThat(BotCheck.suspect(60, 120_000, 0)).isTrue();
        assertThat(BotCheck.suspect(60, 120_000, 59)).isTrue();
        assertThat(BotCheck.suspect(60, 120_000, 60)).isFalse();
    }

    @Test
    void 블록이_적거나_키_입력_수가_없으면_키_조건은_보지_않는다() {
        assertThat(BotCheck.suspect(12, 60_000, 0)).as("손을 놓고 있다 천장에 닿은 판").isFalse();
        assertThat(BotCheck.suspect(60, 120_000, null)).as("키 입력 수를 보내지 않는 옛 클라이언트").isFalse();
    }
}
