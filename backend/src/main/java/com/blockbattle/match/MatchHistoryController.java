package com.blockbattle.match;

import com.blockbattle.auth.web.AccessTokenFilter;
import com.blockbattle.common.api.ApiResponse;
import com.blockbattle.common.error.ApiException;
import com.blockbattle.common.error.ErrorCode;
import com.blockbattle.common.error.GlobalExceptionHandler;
import com.blockbattle.match.MatchHistoryService.MatchDetail;
import com.blockbattle.match.MatchHistoryService.MatchPage;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** API 명세 3.7 GET /users/me/matches(API-007)·3.10 GET /matches/{matchId}(API-016), REQ-015 */
@RestController
public class MatchHistoryController {

    private static final Set<String> MODES = Set.of("SOLO", "AI", "FRIEND");

    private final MatchHistoryService history;

    public MatchHistoryController(MatchHistoryService history) {
        this.history = history;
    }

    @GetMapping(AccessTokenFilter.API_PREFIX + "/users/me/matches")
    public ApiResponse<MatchPage> myMatches(@RequestAttribute(AccessTokenFilter.USER_ID) long userId,
                                            @RequestParam(required = false) String mode,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        if ((mode != null && !MODES.contains(mode)) || page < 0 || size < 1 || size > 50) {
            throw new ApiException(ErrorCode.INVALID_QUERY);
        }
        return ApiResponse.ok(history.list(userId, mode, page, size));
    }

    @GetMapping(AccessTokenFilter.API_PREFIX + "/matches/{matchId}")
    public ApiResponse<MatchDetail> detail(@RequestAttribute(AccessTokenFilter.USER_ID) long userId,
                                           @PathVariable long matchId) {
        return ApiResponse.ok(history.detail(userId, matchId));
    }

    /** page·size가 정수가 아니면 쿼리 오류, matchId가 정수가 아니면 없는 경기로 본다 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> badArgument(MethodArgumentTypeMismatchException e) {
        return GlobalExceptionHandler.toResponse("matchId".equals(e.getName()) ? ErrorCode.MATCH_NOT_FOUND : ErrorCode.INVALID_QUERY);
    }
}
