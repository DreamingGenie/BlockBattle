package com.blockbattle.auth.jwt;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 보안 점검 L-3. 깨지면: 운영 주소(sslip.io 등)로 띄웠는데 JWT_SECRET이 빠져 저장소에 공개된 기본 키로 서명하게 되고,
 * 그러면 누구나 아무 사용자의 Access 토큰을 만들 수 있다.
 */
class JwtProviderTest {

    static JwtProvider provider(String secret, String publicBaseUrl) {
        return new JwtProvider(new JwtProperties(secret, Duration.ofHours(1)), Clock.systemUTC(), publicBaseUrl);
    }

    @Test
    void 개발용_기본_키는_localhost_주소에서만_쓸_수_있다() {
        assertThatCode(() -> provider(JwtProvider.DEV_SECRET, "http://localhost:5173")).doesNotThrowAnyException();
        assertThatCode(() -> provider(JwtProvider.DEV_SECRET, "http://127.0.0.1:5173")).doesNotThrowAnyException();
        assertThatThrownBy(() -> provider(JwtProvider.DEV_SECRET, "https://15-164-33-76.sslip.io"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> provider(JwtProvider.DEV_SECRET, "not a url")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 따로_넣은_키는_어느_주소에서나_쓸_수_있다() {
        assertThatCode(() -> provider("a-real-secret-from-env-0123456789abcdef-xyz", "https://15-164-33-76.sslip.io"))
                .doesNotThrowAnyException();
    }
}
