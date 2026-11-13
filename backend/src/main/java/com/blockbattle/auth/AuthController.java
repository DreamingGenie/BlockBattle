package com.blockbattle.auth;

import com.blockbattle.auth.jwt.JwtProvider;
import com.blockbattle.auth.web.AccessTokenFilter;
import com.blockbattle.common.api.ApiResponse;
import com.blockbattle.common.error.ErrorCode;
import com.blockbattle.common.error.GlobalExceptionHandler;
import com.blockbattle.user.UserRepository;
import java.time.Clock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API 명세 3.1~3.3 (API-001~003) */
@RestController
@RequestMapping(AccessTokenFilter.API_PREFIX + "/auth")
public class AuthController {

    public record GuestLoginRequest(String nickname) {
    }

    public record GuestLoginResponse(long userId, String nickname, String tag, String userType, String accessToken,
                                     long expiresIn) {
    }

    public record TokenResponse(String accessToken, long expiresIn) {
    }

    private final GuestAccountService guests;
    private final RefreshTokens refreshTokens;
    private final JwtProvider jwt;
    private final UserRepository users;
    private final Clock clock;

    public AuthController(GuestAccountService guests, RefreshTokens refreshTokens, JwtProvider jwt, UserRepository users,
                          Clock clock) {
        this.guests = guests;
        this.refreshTokens = refreshTokens;
        this.jwt = jwt;
        this.users = users;
        this.clock = clock;
    }

    @PostMapping("/guest")
    public ResponseEntity<ApiResponse<GuestLoginResponse>> guest(@RequestBody(required = false) GuestLoginRequest request) {
        var guest = guests.create(request == null ? null : request.nickname());
        String refresh = refreshTokens.issue(guest.userId());
        var body = new GuestLoginResponse(guest.userId(), guest.nickname(), guest.tag(), "GUEST",
                jwt.issue(guest.userId()), jwt.expiresInSeconds());
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, RefreshCookie.of(refresh).toString())
                .body(ApiResponse.ok(body));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
            @CookieValue(name = RefreshCookie.NAME, required = false) String presented) {
        var rotated = refreshTokens.rotate(presented);
        users.touchLastLogin(rotated.userId(), clock.instant());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, RefreshCookie.of(rotated.token()).toString())
                .body(ApiResponse.ok(new TokenResponse(jwt.issue(rotated.userId()), jwt.expiresInSeconds())));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestAttribute(AccessTokenFilter.USER_ID) long userId) {
        refreshTokens.revoke(userId);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, RefreshCookie.cleared().toString()).build();
    }

    /** 게스트 로그인 본문이 JSON이 아니면 닉네임 형식 오류로 본다 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> unreadable() {
        return GlobalExceptionHandler.toResponse(ErrorCode.INVALID_NICKNAME);
    }
}
