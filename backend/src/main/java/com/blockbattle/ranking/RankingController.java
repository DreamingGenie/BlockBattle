package com.blockbattle.ranking;

import com.blockbattle.auth.web.AccessTokenFilter;
import com.blockbattle.common.api.ApiResponse;
import com.blockbattle.common.error.ApiException;
import com.blockbattle.common.error.ErrorCode;
import com.blockbattle.common.error.GlobalExceptionHandler;
import com.blockbattle.common.time.ApiTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** API 명세 3.11 GET /rankings/solo (API-010, FR-SOL-02) */
@RestController
@RequestMapping(AccessTokenFilter.API_PREFIX + "/rankings")
public class RankingController {

    public record Entry(long rank, long userId, String nickname, String tag, long score, OffsetDateTime achievedAt) {
    }

    public record Me(long rank, long score) {
    }

    public record SoloRanking(List<Entry> top, Me me) {
    }

    private record UserBest(String nickname, String tag, long score, OffsetDateTime achievedAt) {
    }

    private final SoloRankingCache cache;
    private final NamedParameterJdbcTemplate jdbc;

    public RankingController(SoloRankingCache cache, NamedParameterJdbcTemplate jdbc) {
        this.cache = cache;
        this.jdbc = jdbc;
    }

    /** 순서는 Redis 캐시(BR-08 합성 점수), 표시 값(닉네임·점수·달성 시각)은 원본 MySQL에서 읽는다 */
    @GetMapping("/solo")
    public ApiResponse<SoloRanking> solo(@RequestAttribute(AccessTokenFilter.USER_ID) long userId,
                                         @RequestParam(defaultValue = "10") int limit) {
        if (limit < 1 || limit > 50) throw new ApiException(ErrorCode.INVALID_QUERY);
        List<Long> ids = cache.top(limit);
        Long myRank = cache.rankOf(userId);
        List<Long> lookup = new ArrayList<>(ids);
        if (myRank != null) lookup.add(userId);
        Map<Long, UserBest> bests = load(lookup);

        List<Entry> top = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            UserBest best = bests.get(ids.get(i));
            if (best == null) continue;
            top.add(new Entry(i + 1, ids.get(i), best.nickname(), best.tag(), best.score(), best.achievedAt()));
        }
        Me me = myRank == null || !bests.containsKey(userId) ? null : new Me(myRank, bests.get(userId).score());
        return ApiResponse.ok(new SoloRanking(top, me));
    }

    private Map<Long, UserBest> load(List<Long> userIds) {
        Map<Long, UserBest> result = new HashMap<>();
        if (userIds.isEmpty()) return result;
        jdbc.query("""
                SELECT u.id, u.nickname, u.tag, s.solo_best_score, s.solo_best_achieved_at
                FROM users u JOIN user_stats s ON s.user_id = u.id
                WHERE u.id IN (:ids) AND s.solo_best_achieved_at IS NOT NULL""",
                Map.of("ids", userIds),
                rs -> {
                    result.put(rs.getLong(1), new UserBest(rs.getString(2), rs.getString(3), rs.getLong(4),
                            ApiTime.of(rs.getTimestamp(5).toInstant())));
                });
        return result;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> badQuery() {
        return GlobalExceptionHandler.toResponse(ErrorCode.INVALID_QUERY);
    }
}
