package com.blockbattle.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.blockbattle.auth.kakao.HttpKakaoClient;
import com.blockbattle.auth.kakao.KakaoClient;
import com.blockbattle.common.error.ApiException;
import com.blockbattle.common.error.ErrorCode;
import com.blockbattle.support.ContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * API 명세 3.4·3.5와 FR-USR-02를 대조한다. 카카오 서버 호출은 KakaoClient 모의 객체로 대신한다.
 * 깨지면: 카카오 연결 뒤 계정 유지·중복 연결 거부·연결 없는 로그인 거부 규칙이 어긋났다는 뜻이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ContainersConfig.class)
class KakaoAuthApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean KakaoClient kakao;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM user_stats");
        jdbc.update("DELETE FROM users");
        given(kakao.userIdFor(eq("code-1"), any())).willReturn("kakao-1");
        given(kakao.userIdFor(eq("code-2"), any())).willReturn("kakao-2");
        given(kakao.userIdFor(eq("bad"), any())).willThrow(new ApiException(ErrorCode.KAKAO_AUTH_FAILED));
    }

    @Test
    void 연결하면_같은_userId와_전적을_유지하고_MEMBER가_된다() throws Exception {
        JsonNode guest = guest("오션");
        long userId = guest.get("userId").asLong();
        jdbc.update("UPDATE user_stats SET solo_games = 3, ai_wins = 2 WHERE user_id = ?", userId);

        MockHttpServletResponse res = link(guest.get("accessToken").asText(), "code-1");
        assertThat(res.getStatus()).isEqualTo(200);
        JsonNode data = data(res);
        assertThat(data.get("userId").asLong()).isEqualTo(userId);
        assertThat(data.get("userType").asText()).isEqualTo("MEMBER");
        assertThat(data.get("provider").asText()).isEqualTo("KAKAO");
        assertThat(jdbc.queryForMap("SELECT user_type, provider, provider_id FROM users WHERE id = ?", userId))
                .containsEntry("user_type", "MEMBER").containsEntry("provider", "KAKAO").containsEntry("provider_id", "kakao-1");
        assertThat(jdbc.queryForMap("SELECT solo_games, ai_wins FROM user_stats WHERE user_id = ?", userId))
                .containsEntry("solo_games", 3).containsEntry("ai_wins", 2);
    }

    @Test
    void 이미_연결된_내_계정과_다른_회원에_연결된_카카오_계정은_409() throws Exception {
        JsonNode first = guest("하나");
        link(first.get("accessToken").asText(), "code-1");
        expectError(link(first.get("accessToken").asText(), "code-2"), 409, "ACCOUNT_ALREADY_LINKED");

        JsonNode second = guest("두리");
        expectError(link(second.get("accessToken").asText(), "code-1"), 409, "KAKAO_ALREADY_LINKED");
    }

    @Test
    void 연결된_카카오_계정으로_로그인하면_그_계정을_이어_받는다() throws Exception {
        JsonNode guest = guest("지아");
        link(guest.get("accessToken").asText(), "code-1");
        int usersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);

        MockHttpServletResponse res = kakaoLogin("code-1");
        assertThat(res.getStatus()).isEqualTo(200);
        JsonNode data = data(res);
        assertThat(data.get("userId").asLong()).isEqualTo(guest.get("userId").asLong());
        assertThat(data.get("nickname").asText()).isEqualTo("지아");
        assertThat(data.get("tag").asText()).isEqualTo(guest.get("tag").asText());
        assertThat(data.get("userType").asText()).isEqualTo("MEMBER");
        assertThat(data.get("expiresIn").asLong()).isEqualTo(3600);
        assertThat(data.get("accessToken").asText()).isNotBlank();
        assertThat(res.getHeader("Set-Cookie")).startsWith("refresh_token=").contains("HttpOnly", "SameSite=Strict");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(usersBefore);
    }

    @Test
    void 연결되지_않은_카카오_계정은_새_계정을_만들지_않고_404() throws Exception {
        expectError(kakaoLogin("code-2"), 404, "KAKAO_NOT_LINKED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isZero();
    }

    @Test
    void 카카오_인가에_실패하면_401_KAKAO_AUTH_FAILED_연결은_Access가_필요하다() throws Exception {
        expectError(kakaoLogin("bad"), 401, "KAKAO_AUTH_FAILED");
        expectError(mvc.perform(post("/api/v1/auth/kakao/login").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andReturn().getResponse(), 401, "KAKAO_AUTH_FAILED");
        expectError(mvc.perform(post("/api/v1/auth/kakao/link").contentType(MediaType.APPLICATION_JSON)
                .content("{\"authorizationCode\":\"code-1\",\"redirectUri\":\"https://x/oauth/kakao\"}"))
                .andReturn().getResponse(), 401, "UNAUTHORIZED");
    }

    @Test
    void 실제_카카오_클라이언트는_코드나_앱_키가_없으면_호출하지_않고_인가_실패다() {
        assertThatThrownBy(() -> new HttpKakaoClient("", "").userIdFor("code", "https://x/oauth/kakao"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo(ErrorCode.KAKAO_AUTH_FAILED);
        assertThatThrownBy(() -> new HttpKakaoClient("key", "").userIdFor(null, "https://x/oauth/kakao"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo(ErrorCode.KAKAO_AUTH_FAILED);
    }

    private JsonNode guest(String nickname) throws Exception {
        return data(mvc.perform(post("/api/v1/auth/guest").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"" + nickname + "\"}")).andReturn().getResponse());
    }

    private MockHttpServletResponse link(String accessToken, String code) throws Exception {
        return mvc.perform(post("/api/v1/auth/kakao/link").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"authorizationCode\":\"" + code + "\",\"redirectUri\":\"https://x/oauth/kakao\"}"))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse kakaoLogin(String code) throws Exception {
        return mvc.perform(post("/api/v1/auth/kakao/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"authorizationCode\":\"" + code + "\",\"redirectUri\":\"https://x/oauth/kakao\"}"))
                .andReturn().getResponse();
    }

    private JsonNode data(MockHttpServletResponse res) throws Exception {
        return json.readTree(res.getContentAsString(StandardCharsets.UTF_8)).get("data");
    }

    private void expectError(MockHttpServletResponse res, int status, String code) throws Exception {
        assertThat(res.getStatus()).isEqualTo(status);
        assertThat(json.readTree(res.getContentAsString()).at("/error/code").asText()).isEqualTo(code);
    }
}
