package com.blockbattle.auth.jwt;

import com.blockbattle.common.error.ApiException;
import com.blockbattle.common.error.ErrorCode;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** Access JWT: HS256, sub = userId, 만료 1시간(API 명세 1장). AI 서버도 같은 비밀 키로 검증한다. */
@Component
@EnableConfigurationProperties(JwtProperties.class)
public class JwtProvider {

    /** application.yml의 개발용 기본 키. 저장소에 공개돼 있어 로컬 밖에서 쓰면 누구나 토큰을 위조할 수 있다(보안 점검 L-3) */
    static final String DEV_SECRET = "local-dev-only-secret-change-me-0123456789abcdef";

    private final SecretKey key;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtProvider(JwtProperties properties, Clock clock, @Value("${app.public-base-url}") String publicBaseUrl) {
        if (DEV_SECRET.equals(properties.secret()) && !isLocal(publicBaseUrl)) {
            throw new IllegalStateException("JWT_SECRET이 없어 개발용 기본 키를 쓰려 했습니다. PUBLIC_BASE_URL이 localhost가 아니면 JWT_SECRET을 넣어야 합니다.");
        }
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.properties = properties;
        this.clock = clock;
    }

    /** 초대 링크 주소가 이 PC를 가리키면 로컬 개발로 본다 */
    static boolean isLocal(String publicBaseUrl) {
        try {
            String host = URI.create(publicBaseUrl).getHost();
            return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public String issue(long userId) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(Long.toString(userId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTtl())))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public long expiresInSeconds() {
        return properties.accessTtl().toSeconds();
    }

    /** 만료면 TOKEN_EXPIRED, 서명·형식이 틀리면 UNAUTHORIZED */
    public long verify(String token) {
        try {
            String subject = Jwts.parser().verifyWith(key).clock(() -> Date.from(clock.instant())).build()
                    .parseSignedClaims(token).getPayload().getSubject();
            return Long.parseLong(subject);
        } catch (ExpiredJwtException e) {
            throw new ApiException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
    }
}
