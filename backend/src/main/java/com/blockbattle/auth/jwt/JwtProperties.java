package com.blockbattle.auth.jwt;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** app.jwt.* — HS256 비밀 키와 Access 토큰 수명(API 명세 1장: 1시간) */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, Duration accessTtl) {
}
