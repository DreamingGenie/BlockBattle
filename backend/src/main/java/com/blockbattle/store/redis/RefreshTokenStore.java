package com.blockbattle.store.redis;

import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * refresh:{userId} — String, TTL 7일(ENT-013). 현재 Refresh 토큰의 SHA-256 해시만 두고 회전마다 교체한다.
 * 토큰 발급·해시 계산은 인증 작업(T-3)이 맡는다.
 */
@Component
public class RefreshTokenStore {

    private final StringRedisTemplate redis;

    public RefreshTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 회전: 이전 해시를 덮어쓰고 TTL을 7일로 다시 잡는다. */
    public void replace(long userId, String sha256Hex) {
        redis.opsForValue().set(RedisKeys.refresh(userId), sha256Hex, RedisKeys.REFRESH_TTL);
    }

    public Optional<String> find(long userId) {
        return Optional.ofNullable(redis.opsForValue().get(RedisKeys.refresh(userId)));
    }

    public void revoke(long userId) {
        redis.delete(RedisKeys.refresh(userId));
    }
}
