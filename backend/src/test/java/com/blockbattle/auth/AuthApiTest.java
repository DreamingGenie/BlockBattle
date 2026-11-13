package com.blockbattle.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blockbattle.store.redis.RedisKeys;
import com.blockbattle.support.ContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * API 명세 3.1~3.3·1장(인증·Refresh 쿠키·응답 봉투)·6장(오류 코드)과 ENT-013을 대조한다.
 * 깨지면: 클라이언트가 기대하는 로그인·재발급·로그아웃 계약이 바뀌었거나 토큰 수명 관리 규칙(FR-USR-01)이 어긋났다는 뜻이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ContainersConfig.class)
class AuthApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Value("${app.jwt.secret}") String secret;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM user_stats");
        jdbc.update("DELETE FROM users");
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void 게스트_로그인은_201과_계정_정보_Access_토큰_Refresh_쿠키를_준다() throws Exception {
        MockHttpServletResponse res = guest("오션");
        assertThat(res.getStatus()).isEqualTo(201);
        JsonNode body = json.readTree(res.getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("success").asBoolean()).isTrue();
        assertThat(body.get("error").isNull()).isTrue();
        JsonNode data = body.get("data");
        long userId = data.get("userId").asLong();
        assertThat(data.get("nickname").asText()).isEqualTo("오션");
        assertThat(data.get("tag").asText()).matches("\\d{4}");
        assertThat(data.get("userType").asText()).isEqualTo("GUEST");
        assertThat(data.get("expiresIn").asLong()).isEqualTo(3600);

        Claims claims = parse(data.get("accessToken").asText());
        assertThat(claims.getSubject()).isEqualTo(Long.toString(userId));
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime()).isEqualTo(3_600_000);

        String setCookie = res.getHeader("Set-Cookie");
        assertThat(setCookie).startsWith("refresh_token=").contains("Path=/api/v1/auth", "Max-Age=604800", "Secure",
                "HttpOnly", "SameSite=Strict");
        String token = res.getCookie("refresh_token").getValue();
        assertThat(redis.opsForValue().get(RedisKeys.refresh(userId))).isEqualTo(RefreshTokens.sha256(token));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_stats WHERE user_id = ?", Integer.class, userId)).isEqualTo(1);
    }

    @Test
    void 같은_닉네임으로_다시_로그인하면_다른_태그의_새_계정이_생긴다() throws Exception {
        JsonNode a = data(guest("지아"));
        JsonNode b = data(guest("지아"));
        assertThat(a.get("userId").asLong()).isNotEqualTo(b.get("userId").asLong());
        assertThat(a.get("tag").asText()).isNotEqualTo(b.get("tag").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"가", "열한글자닉네임입니다요", "ocean!", "오 션", "ㅇㅅ"})
    void 닉네임이_한글_영문_숫자_2_10자가_아니면_INVALID_NICKNAME(String nickname) throws Exception {
        mvc.perform(post("/api/v1/auth/guest").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AuthController.GuestLoginRequest(nickname))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("INVALID_NICKNAME"));
    }

    @Test
    void 닉네임의_태그_10000개가_모두_쓰였으면_409_NICKNAME_TAG_EXHAUSTED() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        Timestamp now = Timestamp.from(Instant.now());
        for (int i = 0; i < 10_000; i++) rows.add(new Object[] {"민수", String.format("%04d", i), now});
        jdbc.batchUpdate("INSERT INTO users (nickname, tag, created_at) VALUES (?, ?, ?)", rows);

        MockHttpServletResponse res = guest("민수");
        assertThat(res.getStatus()).isEqualTo(409);
        assertThat(json.readTree(res.getContentAsString()).at("/error/code").asText()).isEqualTo("NICKNAME_TAG_EXHAUSTED");
    }

    @Test
    void 재발급은_새_Access와_회전한_쿠키를_주고_쓴_Refresh는_다시_못_쓴다() throws Exception {
        MockHttpServletResponse login = guest("태오");
        Cookie first = login.getCookie("refresh_token");

        MockHttpServletResponse refreshed = mvc.perform(post("/api/v1/auth/refresh").cookie(first)).andReturn().getResponse();
        assertThat(refreshed.getStatus()).isEqualTo(200);
        JsonNode data = data(refreshed);
        assertThat(data.get("expiresIn").asLong()).isEqualTo(3600);
        assertThat(parse(data.get("accessToken").asText()).getSubject()).isEqualTo(data(login).get("userId").asText());
        Cookie second = refreshed.getCookie("refresh_token");
        assertThat(second.getValue()).isNotEqualTo(first.getValue());

        expectTokenExpired(mvc.perform(post("/api/v1/auth/refresh").cookie(first)).andReturn().getResponse());
        assertThat(mvc.perform(post("/api/v1/auth/refresh").cookie(second)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void 쿠키가_없거나_만료됐으면_TOKEN_EXPIRED() throws Exception {
        expectTokenExpired(mvc.perform(post("/api/v1/auth/refresh")).andReturn().getResponse());
        expectTokenExpired(mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("refresh_token", "garbage")))
                .andReturn().getResponse());

        MockHttpServletResponse login = guest("하나");
        redis.delete(RedisKeys.refresh(data(login).get("userId").asLong()));
        expectTokenExpired(mvc.perform(post("/api/v1/auth/refresh").cookie(login.getCookie("refresh_token")))
                .andReturn().getResponse());
    }

    @Test
    void 로그아웃은_204로_Refresh를_지우고_쿠키를_비운다() throws Exception {
        MockHttpServletResponse login = guest("두리");
        JsonNode data = data(login);
        MockHttpServletResponse res = mvc.perform(post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + data.get("accessToken").asText())).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(204);
        assertThat(res.getContentAsString()).isEmpty();
        assertThat(res.getHeader("Set-Cookie")).startsWith("refresh_token=;").contains("Max-Age=0", "Path=/api/v1/auth");
        assertThat(redis.hasKey(RedisKeys.refresh(data.get("userId").asLong()))).isFalse();
        expectTokenExpired(mvc.perform(post("/api/v1/auth/refresh").cookie(login.getCookie("refresh_token")))
                .andReturn().getResponse());
    }

    @Test
    void 보호된_요청은_토큰이_없거나_위조면_UNAUTHORIZED_만료면_TOKEN_EXPIRED() throws Exception {
        mvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        SecretKey other = Keys.hmacShaKeyFor("another-secret-another-secret-another-secret".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder().subject("1").expiration(Date.from(Instant.now().plusSeconds(600))).signWith(other).compact();
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        String expired = Jwts.builder().subject("1").issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .expiration(Date.from(Instant.now().minusSeconds(3600))).signWith(key()).compact();
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));
    }

    private MockHttpServletResponse guest(String nickname) throws Exception {
        return mvc.perform(post("/api/v1/auth/guest").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AuthController.GuestLoginRequest(nickname)))).andReturn().getResponse();
    }

    private JsonNode data(MockHttpServletResponse res) throws Exception {
        return json.readTree(res.getContentAsString(StandardCharsets.UTF_8)).get("data");
    }

    private void expectTokenExpired(MockHttpServletResponse res) throws Exception {
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(json.readTree(res.getContentAsString()).at("/error/code").asText()).isEqualTo("TOKEN_EXPIRED");
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private Claims parse(String token) {
        return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
    }
}
