package com.blockbattle.store.redis;

import java.time.Clock;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 접속·플레이 중 인원(T-49). member가 userId라 같은 사람이 탭을 여러 개 열어도 1명으로 센다.
 * 갱신·오래된 항목 정리·집계를 Lua 한 번으로 처리해 heartbeat 한 번이 Redis 왕복 한 번이다.
 */
@Component
public class PresenceStore {

    public record Counts(long online, long playing) {
    }

    private static final RedisScript<List> HEARTBEAT = RedisScript.of("""
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
            if ARGV[4] == '1' then
              redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])
            else
              redis.call('ZREM', KEYS[2], ARGV[1])
            end
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. ARGV[3])
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', '(' .. ARGV[3])
            return {redis.call('ZCARD', KEYS[1]), redis.call('ZCARD', KEYS[2])}
            """, List.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public PresenceStore(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /** 이 사용자의 마지막 접속 시각을 지금으로 두고, 플레이 중 여부를 반영한 뒤 두 수를 돌려준다 */
    public Counts heartbeat(long userId, boolean playing) {
        long now = clock.millis();
        long cutoff = now - RedisKeys.PRESENCE_WINDOW.toMillis();
        List<?> counts = redis.execute(HEARTBEAT, List.of(RedisKeys.PRESENCE_ONLINE, RedisKeys.PRESENCE_PLAYING),
                Long.toString(userId), Long.toString(now), Long.toString(cutoff), playing ? "1" : "0");
        return new Counts(((Number) counts.get(0)).longValue(), ((Number) counts.get(1)).longValue());
    }
}
