package com.blockbattle.match;

import com.blockbattle.auth.web.AccessTokenFilter;
import com.blockbattle.common.api.ApiResponse;
import com.blockbattle.common.error.ErrorCode;
import com.blockbattle.common.error.GlobalExceptionHandler;
import com.blockbattle.common.time.ApiTime;
import com.blockbattle.ranking.SoloRankingCache;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** API 명세 3.8·3.9 (API-008·009) */
@RestController
@RequestMapping(AccessTokenFilter.API_PREFIX + "/matches")
public class MatchController {

    public record StartRequest(String mode, String aiDifficulty) {
    }

    public record StartResponse(long matchId, long seed, OffsetDateTime startedAt) {
    }

    public record ResultResponse(long matchId, boolean ranked, boolean newBest, Long soloRank) {
    }

    private final MatchService matches;
    private final SoloRankingCache ranking;

    public MatchController(MatchService matches, SoloRankingCache ranking) {
        this.matches = matches;
        this.ranking = ranking;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<StartResponse>> start(@RequestAttribute(AccessTokenFilter.USER_ID) long userId,
                                                            @RequestBody(required = false) StartRequest request) {
        var req = request == null ? new StartRequest(null, null) : request;
        var started = matches.start(userId, req.mode(), req.aiDifficulty());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                new StartResponse(started.matchId(), started.seed(), ApiTime.of(started.startedAt()))));
    }

    /** soloRank는 싱글이고 newBest일 때만 값이 있다. 랭킹 캐시는 DB 커밋이 끝난 뒤 반영한다. */
    @PostMapping("/{matchId}/result")
    public ApiResponse<ResultResponse> result(@RequestAttribute(AccessTokenFilter.USER_ID) long userId,
                                              @PathVariable long matchId,
                                              @RequestBody(required = false) MatchService.ResultInput request) {
        var saved = matches.saveResult(userId, matchId, request);
        Long soloRank = saved.newBest() ? ranking.recordNewBest(saved.userId(), saved.score()) : null;
        return ApiResponse.ok(new ResultResponse(saved.matchId(), saved.ranked(), saved.newBest(), soloRank));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> unreadable(HttpServletRequest request) {
        boolean result = request.getRequestURI().endsWith("/result");
        return GlobalExceptionHandler.toResponse(result ? ErrorCode.INVALID_RESULT : ErrorCode.INVALID_MODE);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> badMatchId() {
        return GlobalExceptionHandler.toResponse(ErrorCode.MATCH_NOT_FOUND);
    }
}
