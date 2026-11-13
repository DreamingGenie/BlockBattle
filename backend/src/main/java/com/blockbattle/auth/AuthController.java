package com.blockbattle.auth;

import com.blockbattle.auth.jwt.JwtProvider;
import com.blockbattle.auth.kakao.KakaoAccountService;
import com.blockbattle.auth.web.AccessTokenFilter;
import com.blockbattle.common.api.ApiResponse;
import com.blockbattle.common.error.ErrorCode;
import com.blockbattle.common.error.GlobalExceptionHandler;
import com.blockbattle.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
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
    private final KakaoAccountService kakaoAccounts;

    public AuthController(GuestAccountService guests, RefreshTokens refreshTokens, JwtProvider jwt, UserRepository users,
                          Clock clock, KakaoAccountService kakaoAccounts) {
        this.kakaoAccounts = kakaoAccounts;
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

    public record KakaoRequest(String authorizationCode, String redirectUri) {
    }

    public record KakaoLinkResponse(long userId, String userType, String provider) {
    }

    /** API 명세 3.4: 현재 계정에 카카오 연결(Should) */
    @PostMapping("/kakao/link")
    public ApiResponse<KakaoLinkResponse> kakaoLink(@RequestAttribute(AccessTokenFilter.USER_ID) long userId,
                                                    @RequestBody(required = false) KakaoRequest request) {
        var kakaoRequest = request == null ? new KakaoRequest(null, null) : request;
        var linked = kakaoAccounts.link(userId, kakaoRequest.authorizationCode(), kakaoRequest.redirectUri());
        return ApiResponse.ok(new KakaoLinkResponse(linked.id(), linked.userType(), linked.provider()));
    }

    /** API 명세 3.5: 연결된 카카오 계정으로 로그인. 응답 본문·쿠키는 3.1과 같고 상태는 200(T-4 계획 unlinked) */
    @PostMapping("/kakao/login")
    public ResponseEntity<ApiResponse<GuestLoginResponse>> kakaoLogin(@RequestBody(required = false) KakaoRequest request) {
        var kakaoRequest = request == null ? new KakaoRequest(null, null) : request;
        var user = kakaoAccounts.login(kakaoRequest.authorizationCode(), kakaoRequest.redirectUri());
        String refresh = refreshTokens.issue(user.id());
        var body = new GuestLoginResponse(user.id(), user.nickname(), user.tag(), user.userType(),
                jwt.issue(user.id()), jwt.expiresInSeconds());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, RefreshCookie.of(refresh).toString())
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

    /** 본문이 JSON이 아니면: 카카오 요청은 인가 실패, 게스트 로그인은 닉네임 형식 오류로 본다 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> unreadable(HttpServletRequest request) {
        boolean kakao = request.getRequestURI().contains("/auth/kakao/");
        return GlobalExceptionHandler.toResponse(kakao ? ErrorCode.KAKAO_AUTH_FAILED : ErrorCode.INVALID_NICKNAME);
    }
}
