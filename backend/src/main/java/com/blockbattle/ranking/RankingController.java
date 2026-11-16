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

/** API 명세 3.11 GET /rankings/solo (API-010, FR-SOL-02), GET /rankings/ai (T-35) */
@RestController
@RequestMapping(AccessTokenFilter.API_PREFIX + "/rankings")
public class RankingController {

    public record Entry(long rank, long userId, String nickname, String tag, long score, OffsetDateTime achievedAt) {
    }

    public record Me(long rank, long score) {
    }

    public record SoloRanking(List<Entry> top, Me me) {
    }

    public record AiEntry(long rank, long userId, String nickname, String tag, long durationMs, OffsetDateTime achievedAt) {
    }

    public record AiMe(long rank, long durationMs) {
    }

    public record AiRanking(String difficulty, List<AiEntry> top, AiMe me) {
    }

    private static final java.util.Set<String> DIFFICULTIES = java.util.Set.of("EASY", "NORMAL", "HARD", "IMPOSSIBLE");

    /** 신고 플레이 시간이 서버 경과 시간보다 이만큼 넘게 짧으면 랭킹에서 뺀다(시작·결과 요청 왕복과 화면 준비 여유) */
    static final long REPORTED_SHORTFALL_LIMIT_MS = 5_000;

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

    /**
     * 난이도별 AI 최단 승리 시간. 사용자마다 가장 짧은 승리 한 판(같으면 먼저 끝난 판)만 남기고,
     * 짧은 시간 → 먼저 달성 → user_id 순으로 순위를 매긴다. ranked=false 경기와 신고 시간을 줄인 경기는 뺀다.
     */
    @GetMapping("/ai")
    public ApiResponse<AiRanking> ai(@RequestAttribute(AccessTokenFilter.USER_ID) long userId,
                                     @RequestParam(required = false) String difficulty,
                                     @RequestParam(defaultValue = "10") int limit) {
        if (limit < 1 || limit > 50 || difficulty == null || !DIFFICULTIES.contains(difficulty)) throw new ApiException(ErrorCode.INVALID_QUERY);
        List<AiEntry> top = new ArrayList<>();
        AiMe[] me = {null};
        jdbc.query("""
                SELECT r.pos, r.user_id, u.nickname, u.tag, r.duration_ms, r.ended_at FROM (
                    SELECT b.*, ROW_NUMBER() OVER (ORDER BY b.duration_ms, b.ended_at, b.user_id) AS pos FROM (
                        SELECT m.winner_user_id AS user_id, m.duration_ms, m.ended_at,
                               ROW_NUMBER() OVER (PARTITION BY m.winner_user_id ORDER BY m.duration_ms, m.ended_at, m.id) AS rn
                        FROM matches m
                        WHERE m.mode = 'AI' AND m.ai_difficulty = :difficulty AND m.status = 'FINISHED' AND m.ranked
                          AND m.winner_user_id IS NOT NULL AND m.duration_ms IS NOT NULL
                          AND TIMESTAMPDIFF(MICROSECOND, m.started_at, m.ended_at) <= (m.duration_ms + :shortfall) * 1000
                    ) b WHERE b.rn = 1
                ) r JOIN users u ON u.id = r.user_id
                WHERE r.pos <= :limit OR r.user_id = :me
                ORDER BY r.pos""",
                Map.of("difficulty", difficulty, "shortfall", REPORTED_SHORTFALL_LIMIT_MS, "limit", limit, "me", userId),
                rs -> {
                    long pos = rs.getLong(1);
                    long id = rs.getLong(2);
                    long ms = rs.getLong(5);
                    if (pos <= limit) {
                        top.add(new AiEntry(pos, id, rs.getString(3), rs.getString(4), ms, ApiTime.of(rs.getTimestamp(6).toInstant())));
                    }
                    if (id == userId) me[0] = new AiMe(pos, ms);
                });
        return ApiResponse.ok(new AiRanking(difficulty, top, me[0]));
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
