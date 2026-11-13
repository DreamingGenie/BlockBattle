package com.blockbattle.store.redis;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.connection.RedisZSetCommands.ZAddArgs;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * ranking:solo — Sorted Set(member = userId), ranking:solo:seq — String 카운터. 둘 다 TTL 없음(ENT-011·012).
 * 원본은 MySQL user_stats다(POL-008). 쓰기는 ZADD GT라서 늦게 도착한 낮은 점수가 높은 점수를 덮지 않는다.
 */
@Component
public class RankingStore {

    private static final RedisScript<Long> RAISE_SEQ_TO = RedisScript.of("""
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            if current < tonumber(ARGV[1]) then redis.call('SET', KEYS[1], ARGV[1]) return tonumber(ARGV[1]) end
            return current
            """, Long.class);

    private final StringRedisTemplate redis;

    public RankingStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public long nextAchievementSeq() {
        Long seq = redis.opsForValue().increment(RedisKeys.RANKING_SOLO_SEQ);
        return seq == null ? 0 : seq;
    }

    /** 복구 뒤 카운터가 복구한 순번보다 작으면 올린다(낮추지는 않는다) */
    public void raiseSeqTo(long seq) {
        redis.execute(RAISE_SEQ_TO, List.of(RedisKeys.RANKING_SOLO_SEQ), Long.toString(seq));
    }

    /** ZADD GT: 기존 점수보다 클 때만 바꾼다(없으면 넣는다) */
    public void put(long userId, double compositeScore) {
        byte[] key = RedisKeys.RANKING_SOLO.getBytes(StandardCharsets.UTF_8);
        byte[] member = Long.toString(userId).getBytes(StandardCharsets.UTF_8);
        redis.execute((RedisCallback<Boolean>) c -> c.zSetCommands().zAdd(key, compositeScore, member, ZAddArgs.empty().gt()));
    }

    /** 높은 점수부터 상위 n명의 userId. */
    public List<Long> top(int n) {
        Set<TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeWithScores(RedisKeys.RANKING_SOLO, 0, n - 1L);
        return tuples == null ? List.of()
                : tuples.stream().map(t -> Long.parseLong(t.getValue())).toList();
    }

    /** 0부터 세는 순위(높은 점수가 0). 없으면 비어 있음. */
    public Optional<Long> rankOf(long userId) {
        return Optional.ofNullable(redis.opsForZSet().reverseRank(RedisKeys.RANKING_SOLO, Long.toString(userId)));
    }

    public boolean isEmpty() {
        Long size = redis.opsForZSet().zCard(RedisKeys.RANKING_SOLO);
        return size == null || size == 0;
    }
}
