package com.blockbattle.store.redis;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Component;

/**
 * ranking:solo — Sorted Set(member = userId), ranking:solo:seq — String 카운터. 둘 다 TTL 없음(ENT-011·012).
 * 합성 점수 계산·MySQL 복구는 랭킹 작업(T-6)이 맡는다. 원본은 MySQL user_stats다(POL-008).
 */
@Component
public class RankingStore {

    private final StringRedisTemplate redis;

    public RankingStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public long nextAchievementSeq() {
        Long seq = redis.opsForValue().increment(RedisKeys.RANKING_SOLO_SEQ);
        return seq == null ? 0 : seq;
    }

    public void put(long userId, double compositeScore) {
        redis.opsForZSet().add(RedisKeys.RANKING_SOLO, Long.toString(userId), compositeScore);
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
