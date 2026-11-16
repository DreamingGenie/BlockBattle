package com.blockbattle.match;

/**
 * 싱글·vs AI 자동 플레이(봇) 의심 판정(T-39). 의심이면 결과는 경기 행에만 남기고 ranked = false, 전적·최고 점수·랭킹에는 넣지 않는다.
 * ① 놓은 블록이 MIN_PIECES 이상이고 초당 블록 수(서버 경과 시간 기준)가 MAX_PPS를 넘는다 — 사람 상위권도 초당 3~4개다.
 * ② 클라이언트가 실제 키 입력 수(isTrusted keydown)를 보냈고, 놓은 블록이 MIN_PIECES 이상인데 키 입력이 놓은 블록보다 적다.
 *    콘솔 스크립트가 게임 객체를 직접 조작하면 키 입력 없이 블록이 놓인다. 손을 놓고 있으면 블록이 중력으로 떨어지지만
 *    그 경우 MIN_PIECES에 닿기 전에 천장에 닿는다. 키 입력 수를 보내지 않는 옛 클라이언트는 ②를 건너뛴다.
 * 클라이언트가 보내는 값이라 우회할 수 있다. 콘솔에 붙여 넣는 수준의 봇을 거르는 1차 방어다.
 */
public final class BotCheck {

    static final int MIN_PIECES = 20;
    static final double MAX_PPS = 4.0;

    private BotCheck() {
    }

    public static boolean suspect(long pieces, long serverElapsedMs, Integer keyPresses) {
        if (pieces < MIN_PIECES) return false;
        double pps = pieces * 1000.0 / Math.max(1, serverElapsedMs);
        if (pps > MAX_PPS) return true;
        return keyPresses != null && keyPresses < pieces;
    }
}
