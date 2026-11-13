package com.blockbattle.auth;

import com.blockbattle.store.redis.RedisKeys;
import java.time.Duration;
import org.springframework.http.ResponseCookie;

/** Refresh 쿠키: refresh_token, HttpOnly, Secure, SameSite=Strict, Path=/api/v1/auth, 만료 7일(API 명세 1장) */
public final class RefreshCookie {

    public static final String NAME = "refresh_token";
    public static final String PATH = "/api/v1/auth";

    private RefreshCookie() {
    }

    public static ResponseCookie of(String token) {
        return build(token, RedisKeys.REFRESH_TTL);
    }

    /** 로그아웃: 쿠키를 비운다(Max-Age=0) */
    public static ResponseCookie cleared() {
        return build("", Duration.ZERO);
    }

    private static ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true).secure(true).sameSite("Strict").path(PATH).maxAge(maxAge).build();
    }
}
