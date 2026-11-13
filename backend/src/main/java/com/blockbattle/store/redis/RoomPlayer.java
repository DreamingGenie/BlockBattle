package com.blockbattle.store.redis;

/** room:{code}:players 의 값 하나(ENT-007): userId → {nickname, tag, ready, connected, rematch} */
public record RoomPlayer(String nickname, String tag, boolean ready, boolean connected, boolean rematch) {
}
