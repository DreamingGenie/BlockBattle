package com.blockbattle.auth.jwt;

import com.blockbattle.common.error.ApiException;
import com.blockbattle.common.error.ErrorCode;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** Access JWT: HS256, sub = userId, 만료 1시간(API 명세 1장). AI 서버도 같은 비밀 키로 검증한다. */
@Component
@EnableConfigurationProperties(JwtProperties.class)
public class JwtProvider {

    private final SecretKey key;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtProvider(JwtProperties properties, Clock clock) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.properties = properties;
        this.clock = clock;
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
