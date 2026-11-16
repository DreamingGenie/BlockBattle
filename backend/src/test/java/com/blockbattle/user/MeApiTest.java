package com.blockbattle.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import org.springframework.test.web.servlet.MockMvc;

/**
 * API 명세 3.6과 FR-USR-03·REQ-018을 대조한다.
 * 깨지면: 내 정보 화면이 받는 계정·전적 필드가 바뀌었거나, 결과 저장 뒤 전적이 MySQL 원본과 다르게 보인다는 뜻이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ContainersConfig.class)
class MeApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM match_players");
        jdbc.update("DELETE FROM matches");
        jdbc.update("DELETE FROM user_stats");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void 기록_없는_게스트는_계정_정보와_0인_전적을_받는다() throws Exception {
        JsonNode guest = guest("오션");
        JsonNode me = me(guest.get("accessToken").asText());
        assertThat(me.get("userId").asLong()).isEqualTo(guest.get("userId").asLong());
        assertThat(me.get("nickname").asText()).isEqualTo("오션");
        assertThat(me.get("tag").asText()).isEqualTo(guest.get("tag").asText());
        assertThat(me.get("userType").asText()).isEqualTo("GUEST");
        assertThat(json.convertValue(me.get("stats"), java.util.Map.class)).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "soloGames", 0, "soloBestScore", 0, "aiWins", 0, "aiLosses", 0, "friendWins", 0, "friendLosses", 0));
    }

    @Test
    void 싱글_AI_결과를_저장하면_MySQL_전적과_같은_값을_보여준다() throws Exception {
        JsonNode guest = guest("지아");
        String token = guest.get("accessToken").asText();
        long solo = start(token, "{\"mode\":\"SOLO\"}");
        send(token, "/api/v1/matches/" + solo + "/result",
                "{\"durationMs\":184000,\"player\":{\"score\":45200,\"linesCleared\":62,\"level\":7,\"piecesPlaced\":171,\"maxCombo\":5}}");
        long ai = start(token, "{\"mode\":\"AI\",\"aiDifficulty\":\"EASY\"}");
        send(token, "/api/v1/matches/" + ai + "/result", """
                {"result":"WIN","durationMs":184000,
                 "player":{"score":100,"linesCleared":1,"level":1,"piecesPlaced":10,"attackSent":0,"attackReceived":0,"maxCombo":0},
                 "ai":{"score":50,"linesCleared":0,"level":1,"piecesPlaced":9,"attackSent":0,"attackReceived":0,"maxCombo":0}}""");
        // 친구 대전 전적은 T-13이 쓰므로 여기서는 원본에 직접 넣어 읽기만 확인한다
        jdbc.update("UPDATE user_stats SET friend_wins = 3, friend_losses = 2 WHERE user_id = ?", guest.get("userId").asLong());

        JsonNode stats = me(token).get("stats");
        var row = jdbc.queryForMap("SELECT * FROM user_stats WHERE user_id = ?", guest.get("userId").asLong());
        assertThat(stats.get("soloGames").asInt()).isEqualTo(row.get("solo_games")).isEqualTo(1);
        assertThat(stats.get("soloBestScore").asInt()).isEqualTo(row.get("solo_best_score")).isEqualTo(45200);
        assertThat(stats.get("aiWins").asInt()).isEqualTo(row.get("ai_wins")).isEqualTo(1);
        assertThat(stats.get("aiLosses").asInt()).isEqualTo(row.get("ai_losses")).isEqualTo(0);
        assertThat(stats.get("friendWins").asInt()).isEqualTo(3);
        assertThat(stats.get("friendLosses").asInt()).isEqualTo(2);
    }

    @Test
    void 카카오_연결_회원은_MEMBER로_보인다() throws Exception {
        JsonNode guest = guest("하나");
        jdbc.update("UPDATE users SET user_type = 'MEMBER', provider = 'KAKAO', provider_id = 'k-1' WHERE id = ?",
                guest.get("userId").asLong());
        assertThat(me(guest.get("accessToken").asText()).get("userType").asText()).isEqualTo("MEMBER");
    }

    @Test
    void Access_토큰이_없거나_사용자가_없으면_401_UNAUTHORIZED() throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/api/v1/users/me")).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(json.readTree(res.getContentAsString()).at("/error/code").asText()).isEqualTo("UNAUTHORIZED");

        JsonNode guest = guest("두리");
        jdbc.update("DELETE FROM user_stats WHERE user_id = ?", guest.get("userId").asLong());
        jdbc.update("DELETE FROM users WHERE id = ?", guest.get("userId").asLong());
        res = mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + guest.get("accessToken").asText()))
                .andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(json.readTree(res.getContentAsString()).at("/error/code").asText()).isEqualTo("UNAUTHORIZED");
    }

    private JsonNode me(String token) throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        return json.readTree(res.getContentAsString(StandardCharsets.UTF_8)).get("data");
    }

    private long start(String token, String body) throws Exception {
        long matchId = json.readTree(send(token, "/api/v1/matches", body).getContentAsString()).at("/data/matchId").asLong();
        // 184초 플레이를 실제로 한 것처럼 시작 시각을 당긴다(T-30 상한 ⑤: 플레이 시간 ≤ 서버 경과 + 2초)
        jdbc.update("UPDATE matches SET started_at = ? WHERE id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(200)), matchId);
        return matchId;
    }

    private MockHttpServletResponse send(String token, String url, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
    }

    private JsonNode guest(String nickname) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/auth/guest").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"" + nickname + "\"}")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).get("data");
    }
}
