package com.blockbattle.ranking;

import com.blockbattle.store.redis.RankingStore;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 싱글 랭킹 Redis 캐시(BR-08, POL-008). 원본은 MySQL user_stats이고, 여기는 조회 캐시다.
 * 합성 점수 = 최고 점수 × 10,000,000 + (9,999,999 − 달성 순번). 동점이면 먼저 달성한 사람이 위다.
 */
@Component
public class SoloRankingCache {

    static final long SCORE_SHIFT = 10_000_000L;
    static final long SEQ_CEILING = 9_999_999L;

    private record Best(long userId, long score, Timestamp achievedAt) {
    }

    private final RankingStore store;
    private final JdbcTemplate jdbc;

    public SoloRankingCache(RankingStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    static double composite(long bestScore, long achievementSeq) {
        return bestScore * SCORE_SHIFT + (SEQ_CEILING - achievementSeq);
    }

    /**
     * 캐시가 비어 있으면 MySQL에서 달성 시각 오름차순(같으면 user_id)으로 순번 1, 2, …를 다시 매겨 채운다.
     * 기록이 없는 사용자(달성 시각 NULL)는 넣지 않는다. 카운터는 복구한 마지막 순번 뒤로 맞춘다.
     */
    public void ensureLoaded() {
        if (!store.isEmpty()) return;
        List<Best> bests = jdbc.query("""
                SELECT user_id, solo_best_score, solo_best_achieved_at FROM user_stats
                WHERE solo_best_achieved_at IS NOT NULL
                ORDER BY solo_best_achieved_at ASC, user_id ASC""",
                (rs, i) -> new Best(rs.getLong(1), rs.getLong(2), rs.getTimestamp(3)));
        long seq = 0;
        for (Best best : bests) {
            store.put(best.userId(), composite(best.score(), ++seq));
        }
        store.raiseSeqTo(seq);
    }

    /** 최고 점수를 새로 달성했을 때(DB 커밋 뒤) 부른다. 1부터 세는 순위를 돌려준다. */
    public long recordNewBest(long userId, long bestScore) {
        ensureLoaded();
        store.put(userId, composite(bestScore, store.nextAchievementSeq()));
        return store.rankOf(userId).map(rank -> rank + 1).orElseThrow();
    }

    /** 1부터 세는 순위. 랭킹에 없으면 null */
    public Long rankOf(long userId) {
        ensureLoaded();
        return store.rankOf(userId).map(rank -> rank + 1).orElse(null);
    }

    public List<Long> top(int limit) {
        ensureLoaded();
        return store.top(limit);
    }
}
