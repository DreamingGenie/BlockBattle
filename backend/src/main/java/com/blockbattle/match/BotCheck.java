package com.blockbattle.match;

/**
 * 싱글·vs AI 자동 플레이(봇) 판정(T-39, T-40). 의심이면 결과는 경기 행에만 남기고 ranked = false, 전적·최고 점수·랭킹에는 넣지 않는다.
 *
 * <p>바로 차단(사람이 할 수 없는 경우만) — 놓은 블록이 MIN_PIECES 이상일 때
 * <ul>
 *   <li>초당 블록 수(서버 경과 시간 기준)가 HARD_MAX_PPS 초과. 상위 고수도 초당 3~5개다.</li>
 *   <li>클라이언트가 보낸 실제 키 입력 수가 놓은 블록보다 적다. 사람은 블록마다 최소 한 번(하드 드롭)은 누른다.
 *       콘솔 스크립트가 게임 객체를 직접 조작하면 키 입력 없이 블록이 놓인다.</li>
 * </ul>
 *
 * <p>점수제(신호 하나로는 판정하지 않는다) — 봇 쪽 신호 개수를 센다
 * <ul>
 *   <li>초당 블록 수 > SOFT_PPS</li>
 *   <li>블록 간격 변동계수 < MIN_INTERVAL_CV (고정 지연으로 일정하게 놓음)</li>
 *   <li>키 누름 시간 변동계수 < MIN_HOLD_CV 또는 평균 < MIN_HOLD_MEAN_MS (기계적인 키 입력)</li>
 *   <li>블록당 키 입력 < MIN_INPUTS_PER_PIECE (최단 입력만 씀)</li>
 *   <li>키 입력 수나 입력 요약값이 없음 — 지금 클라이언트는 늘 보내므로, 빠진 결과는 직접 만든 요청이다</li>
 * </ul>
 * 신호가 SOFT_SIGNALS_TO_FLAG 이상이면 봇 쪽이다(app.bot.soft-enforce, 기본 true).
 *
 * <p>2026-11-19 운영 데이터로 다시 정했다: 블록 40개 이상을 놓은 사용자 69명의 최고 초당 블록은 2.48이고, 키 누름 평균의
 * 사용자별 중앙값은 대부분 77~259ms였다. IMPOSSIBLE을 이긴 봇(경기 2188)은 초당 6.55, 키 누름 평균 18ms였다.
 * 이 기준을 백업 데이터 전체에 돌리면 새로 걸리는 사람은 그 봇 계정뿐이었다.
 *
 * <p>클라이언트가 보내는 값이라 우회할 수 있다. 콘솔에 붙여 넣는 수준의 봇을 거르는 1차 방어다.
 */
public final class BotCheck {

    static final int MIN_PIECES = 20;
    static final double HARD_MAX_PPS = 7.0;
    static final double SOFT_PPS = 4.0;
    static final double MIN_INTERVAL_CV = 0.15;
    static final double MIN_HOLD_CV = 0.10;
    static final double MIN_HOLD_MEAN_MS = 50;
    static final double MIN_INPUTS_PER_PIECE = 1.5;
    static final int SOFT_SIGNALS_TO_FLAG = 2;

    /** 클라이언트가 보낸 입력 요약값. 값마다 null이면 표본이 모자란 것이다 */
    public record InputStats(Double intervalCv, Double holdMeanMs, Double holdCv, Double inputsPerPiece) {
    }

    /** pps는 서버가 계산한 초당 블록 수, softSignals는 봇 쪽 신호 개수 */
    public record Verdict(boolean hard, int softSignals, double pps) {

        public boolean bot(boolean softEnforce) {
            return hard || (softEnforce && softSignals >= SOFT_SIGNALS_TO_FLAG);
        }
    }

    private BotCheck() {
    }

    public static Verdict judge(long pieces, long serverElapsedMs, Integer keyPresses, InputStats stats) {
        double pps = pieces * 1000.0 / Math.max(1, serverElapsedMs);
        if (pieces < MIN_PIECES) return new Verdict(false, 0, pps);
        boolean hard = pps > HARD_MAX_PPS || (keyPresses != null && keyPresses < pieces);
        int soft = 0;
        if (pps > SOFT_PPS) soft++;
        if (stats == null || keyPresses == null) soft++;
        if (stats != null) {
            if (stats.intervalCv() != null && stats.intervalCv() < MIN_INTERVAL_CV) soft++;
            if ((stats.holdCv() != null && stats.holdCv() < MIN_HOLD_CV)
                    || (stats.holdMeanMs() != null && stats.holdMeanMs() < MIN_HOLD_MEAN_MS)) soft++;
            if (stats.inputsPerPiece() != null && stats.inputsPerPiece() < MIN_INPUTS_PER_PIECE) soft++;
        }
        return new Verdict(hard, soft, pps);
    }
}
