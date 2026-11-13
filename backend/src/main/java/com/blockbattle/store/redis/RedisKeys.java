package com.blockbattle.store.redis;

import java.time.Duration;

/**
 * Redis 키 이름과 TTL의 단일 정의(ERD 명세 6장, ENT-006~013).
 * 키 문자열을 다른 곳에서 직접 조립하지 않는다.
 */
public final class RedisKeys {

    /** room:{code}·room:{code}:players·user:{userId}:room — 활동마다 30분으로 갱신(POL-024) */
    public static final Duration ROOM_TTL = Duration.ofMinutes(30);
    /** match:{matchId}:state — lock·재접속마다 2시간으로 갱신 */
    public static final Duration MATCH_STATE_TTL = Duration.ofHours(2);
    /** match:{matchId}:disconnect:{userId} — 재접속 유예(BR-06) */
    public static final Duration DISCONNECT_TTL = Duration.ofSeconds(10);
    /** refresh:{userId} — Refresh 토큰 수명 */
    public static final Duration REFRESH_TTL = Duration.ofDays(7);
    /** ws:session:{sessionId} — 연결이 끊긴 뒤 10초(ERD 원문, 쓰임은 WebSocket 작업에서 정한다) */
    public static final Duration WS_SESSION_AFTER_DISCONNECT_TTL = Duration.ofSeconds(10);

    public static final String RANKING_SOLO = "ranking:solo";
    public static final String RANKING_SOLO_SEQ = "ranking:solo:seq";

    private RedisKeys() {
    }

    public static String room(String code) {
        return "room:" + code;
    }

    public static String roomPlayers(String code) {
        return "room:" + code + ":players";
    }

    public static String userRoom(long userId) {
        return "user:" + userId + ":room";
    }

    public static String matchState(long matchId) {
        return "match:" + matchId + ":state";
    }

    public static String disconnect(long matchId, long userId) {
        return "match:" + matchId + ":disconnect:" + userId;
    }

    public static String refresh(long userId) {
        return "refresh:" + userId;
    }

    public static String wsSession(String sessionId) {
        return "ws:session:" + sessionId;
    }
}
