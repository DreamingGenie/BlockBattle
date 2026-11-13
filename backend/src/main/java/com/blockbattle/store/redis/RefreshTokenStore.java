package com.blockbattle.store.redis;

import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
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

    private static final RedisScript<Long> ROTATE_IF_MATCHES = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
              return 1
            end
            return 0
            """, Long.class);

    /**
     * 저장된 해시가 expected일 때만 next로 바꾸고 TTL을 7일로 다시 잡는다. 원자적이라 같은 토큰으로 동시에 재발급하면 한 번만 성공한다.
     */
    public boolean rotateIfMatches(long userId, String expectedSha256Hex, String nextSha256Hex) {
        Long changed = redis.execute(ROTATE_IF_MATCHES, List.of(RedisKeys.refresh(userId)),
                expectedSha256Hex, nextSha256Hex, Long.toString(RedisKeys.REFRESH_TTL.toSeconds()));
        return changed != null && changed == 1L;
    }

    public Optional<String> find(long userId) {
        return Optional.ofNullable(redis.opsForValue().get(RedisKeys.refresh(userId)));
    }

    public void revoke(long userId) {
        redis.delete(RedisKeys.refresh(userId));
    }
}
