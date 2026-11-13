package com.blockbattle.store.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * match:{matchId}:disconnect:{userId} — String, TTL 10초(ENT-010).
 * 만료되면 끊긴 쪽 패배(BR-06). 만료 감지와 패배 처리는 재접속 작업(T-13)이 맡는다.
 */
@Component
public class DisconnectTimerStore {

    private final StringRedisTemplate redis;

    public DisconnectTimerStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void start(long matchId, long userId) {
        redis.opsForValue().set(RedisKeys.disconnect(matchId, userId), Long.toString(userId), RedisKeys.DISCONNECT_TTL);
    }

    /** 재접속하면 패배 타이머를 취소한다. 타이머가 남아 있었으면 true. */
    public boolean cancel(long matchId, long userId) {
        return Boolean.TRUE.equals(redis.delete(RedisKeys.disconnect(matchId, userId)));
    }

    public boolean isRunning(long matchId, long userId) {
        return Boolean.TRUE.equals(redis.hasKey(RedisKeys.disconnect(matchId, userId)));
    }
}
