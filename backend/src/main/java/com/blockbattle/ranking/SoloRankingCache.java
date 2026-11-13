package com.blockbattle.ranking;

import com.blockbattle.store.redis.RankingStore;
import org.springframework.stereotype.Component;

/**
 * 싱글 랭킹 Redis 캐시(BR-08, POL-008). 원본은 MySQL user_stats이고, 여기는 조회 캐시다.
 * 합성 점수 = 최고 점수 × 10,000,000 + (9,999,999 − 달성 순번). 동점이면 먼저 달성한 사람이 위다.
 */
@Component
public class SoloRankingCache {

    static final long SCORE_SHIFT = 10_000_000L;
    static final long SEQ_CEILING = 9_999_999L;

    private final RankingStore store;

    public SoloRankingCache(RankingStore store) {
        this.store = store;
    }

    static double composite(long bestScore, long achievementSeq) {
        return bestScore * SCORE_SHIFT + (SEQ_CEILING - achievementSeq);
    }

    /** 최고 점수를 새로 달성했을 때(DB 커밋 뒤) 부른다. 1부터 세는 순위를 돌려준다. */
    public long recordNewBest(long userId, long bestScore) {
        store.put(userId, composite(bestScore, store.nextAchievementSeq()));
        return store.rankOf(userId).map(rank -> rank + 1).orElseThrow();
    }
}
