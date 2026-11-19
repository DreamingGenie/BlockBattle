package com.blockbattle.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.blockbattle.store.redis.RedisKeys;
import com.blockbattle.support.ContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * API 명세 3.8·3.9, BR-02·07·09, ENT-004·005를 대조한다.
 * 깨지면: 싱글·AI 경기 생성·결과 저장 계약이나 상한 검사·전적 갱신·미저장 만료 규칙이 어긋났다는 뜻이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ContainersConfig.class)
class MatchApiTest {

    static final String SOLO_RESULT = """
            {"durationMs":184000,"player":{"score":45200,"linesCleared":62,"level":7,"piecesPlaced":171,"maxCombo":5}}""";
    static final String SOLO_OVER_LIMIT = """
            {"durationMs":184000,"player":{"score":45200,"linesCleared":62,"level":9,"piecesPlaced":171,"maxCombo":5}}""";

    static String aiResult(String result) {
        return """
                {"result":"%s","durationMs":184000,
                 "player":{"score":45200,"linesCleared":62,"level":7,"piecesPlaced":171,"attackSent":38,"attackReceived":21,"maxCombo":5},
                 "ai":{"score":30100,"linesCleared":48,"level":5,"piecesPlaced":160,"attackSent":21,"attackReceived":38,"maxCombo":3}}"""
                .formatted(result);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired MatchService matchService;

    long userId;
    String token;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM match_players");
        jdbc.update("DELETE FROM matches");
        jdbc.update("DELETE FROM user_stats");
        jdbc.update("DELETE FROM users");
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        JsonNode me = guest("오션");
        userId = me.get("userId").asLong();
        token = me.get("accessToken").asText();
    }

    @Test
    void 싱글_시작은_201로_matchId_시드_시작_시각을_주고_PLAYING_경기와_참가자_1행을_만든다() throws Exception {
        MockHttpServletResponse res = start(token, "{\"mode\":\"SOLO\"}");
        assertThat(res.getStatus()).isEqualTo(201);
        JsonNode data = data(res);
        long matchId = data.get("matchId").asLong();
        assertThat(data.get("seed").asLong()).isBetween(0L, 4_294_967_295L);
        assertThat(data.get("startedAt").asText()).endsWith("+09:00");
        assertThat(jdbc.queryForMap("SELECT mode, status, ai_difficulty, seed FROM matches WHERE id = ?", matchId))
                .containsEntry("mode", "SOLO").containsEntry("status", "PLAYING").containsEntry("ai_difficulty", null)
                .containsEntry("seed", data.get("seed").asLong());
        assertThat(jdbc.queryForList("SELECT slot, user_id, player_type, result, score FROM match_players WHERE match_id = ?", matchId))
                .containsExactly(Map.of("slot", 1, "user_id", userId, "player_type", "USER", "result", "NONE", "score", 0));
    }

    @Test
    void AI_시작은_난이도를_저장하고_AI를_자리_2에_둔다() throws Exception {
        long matchId = data(start(token, "{\"mode\":\"AI\",\"aiDifficulty\":\"IMPOSSIBLE\"}")).get("matchId").asLong();
        assertThat(jdbc.queryForObject("SELECT ai_difficulty FROM matches WHERE id = ?", String.class, matchId)).isEqualTo("IMPOSSIBLE");
        assertThat(jdbc.queryForMap("SELECT user_id, player_type, result FROM match_players WHERE match_id = ? AND slot = 2", matchId))
                .containsEntry("user_id", null).containsEntry("player_type", "AI").containsEntry("result", "NONE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"mode\":\"FRIEND\"}", "{\"mode\":\"AI\"}", "{\"mode\":\"AI\",\"aiDifficulty\":\"GOD\"}",
            "{\"mode\":\"SOLO\",\"aiDifficulty\":\"EASY\"}", "{\"mode\":\"DUO\"}", "{}", "nope"})
    void 친구_대전이거나_AI_난이도가_없으면_INVALID_MODE(String body) throws Exception {
        expectError(start(token, body), 400, "INVALID_MODE");
    }

    @Test
    void 싱글_결과는_FINISHED로_저장되고_최고_점수와_랭킹에_반영된다() throws Exception {
        long matchId = startSolo();
        MockHttpServletResponse res = result(token, matchId, SOLO_RESULT);
        assertThat(res.getStatus()).isEqualTo(200);
        JsonNode data = data(res);
        assertThat(data.get("matchId").asLong()).isEqualTo(matchId);
        assertThat(data.get("ranked").asBoolean()).isTrue();
        assertThat(data.get("newBest").asBoolean()).isTrue();
        assertThat(data.get("soloRank").asLong()).isEqualTo(1);

        Map<String, Object> match = jdbc.queryForMap(
                "SELECT status, end_reason, duration_ms, ranked, winner_user_id, ended_at FROM matches WHERE id = ?", matchId);
        assertThat(match).containsEntry("status", "FINISHED").containsEntry("end_reason", "COMPLETED")
                .containsEntry("duration_ms", 184000).containsEntry("ranked", true).containsEntry("winner_user_id", null);
        assertThat(match.get("ended_at")).isNotNull();
        assertThat(jdbc.queryForMap("SELECT result, score, lines_cleared, level, pieces_placed, max_combo FROM match_players WHERE match_id = ?", matchId))
                .containsEntry("result", "NONE").containsEntry("score", 45200).containsEntry("lines_cleared", 62)
                .containsEntry("level", 7).containsEntry("pieces_placed", 171).containsEntry("max_combo", 5);
        Map<String, Object> stats = jdbc.queryForMap("SELECT solo_games, solo_best_score, solo_best_achieved_at FROM user_stats WHERE user_id = ?", userId);
        assertThat(stats).containsEntry("solo_games", 1).containsEntry("solo_best_score", 45200);
        assertThat(stats.get("solo_best_achieved_at")).isNotNull();
        assertThat(redis.opsForZSet().score(RedisKeys.RANKING_SOLO, Long.toString(userId))).isNotNull();
    }

    @Test
    void 같은_경기는_한_번만_저장되고_남의_경기와_없는_경기는_거부된다() throws Exception {
        long matchId = startSolo();
        result(token, matchId, SOLO_RESULT);
        expectError(result(token, matchId, SOLO_RESULT), 409, "MATCH_ALREADY_FINISHED");

        long other = startSolo();
        String otherToken = guest("지아").get("accessToken").asText();
        expectError(result(otherToken, other, SOLO_RESULT), 403, "NOT_PARTICIPANT");
        expectError(result(token, 999_999, SOLO_RESULT), 404, "MATCH_NOT_FOUND");
    }

    @Test
    void 상한을_넘은_결과는_저장하되_ranked_false로_최고_점수와_랭킹에서_뺀다() throws Exception {
        long matchId = startSolo();
        JsonNode data = data(result(token, matchId, SOLO_OVER_LIMIT));
        assertThat(data.get("ranked").asBoolean()).isFalse();
        assertThat(data.get("newBest").asBoolean()).isFalse();
        assertThat(data.get("soloRank").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT ranked FROM matches WHERE id = ?", Boolean.class, matchId)).isFalse();
        assertThat(jdbc.queryForMap("SELECT solo_games, solo_best_score, solo_best_achieved_at FROM user_stats WHERE user_id = ?", userId))
                .containsEntry("solo_games", 1).containsEntry("solo_best_score", 0).containsEntry("solo_best_achieved_at", null);
        assertThat(redis.hasKey(RedisKeys.RANKING_SOLO)).isFalse();
    }

    /** 깨지면: 시작 직후 긴 플레이 시간을 신고하는 위조가 다시 랭킹에 들어간다(보안 점검 H-1, 상한 ⑤) */
    @Test
    void 시작_직후_긴_플레이_시간을_신고하면_저장하되_ranked_false() throws Exception {
        long matchId = data(start(token, "{\"mode\":\"SOLO\"}")).get("matchId").asLong();
        JsonNode data = data(result(token, matchId, SOLO_RESULT));
        assertThat(data.get("ranked").asBoolean()).isFalse();
        assertThat(data.get("soloRank").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT solo_best_score FROM user_stats WHERE user_id = ?", Integer.class, userId)).isZero();
        assertThat(redis.hasKey(RedisKeys.RANKING_SOLO)).isFalse();
    }

    /** 보안 점검 보고서 H-1의 위조 요청 그대로. 깨지면: 게스트 하나로 랭킹 1위를 만들 수 있다 */
    @Test
    void 보고서의_위조_요청은_저장되지만_랭킹에_들어가지_않는다() throws Exception {
        long matchId = data(start(token, "{\"mode\":\"SOLO\"}")).get("matchId").asLong();
        MockHttpServletResponse res = result(token, matchId, """
                {"durationMs":1000,"player":{"score":2147483647,"linesCleared":4,"level":1,"piecesPlaced":10,"maxCombo":50000000}}""");
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(data(res).get("ranked").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT ranked FROM matches WHERE id = ?", Boolean.class, matchId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT solo_best_score FROM user_stats WHERE user_id = ?", Integer.class, userId)).isZero();
        assertThat(redis.hasKey(RedisKeys.RANKING_SOLO)).isFalse();
    }

    /** 깨지면: vs AI도 시작 직후 결과를 신고할 수 있다(보안 점검 L-6 중 ranked 표시) */
    @Test
    void AI_경기도_시작_직후_긴_플레이_시간이면_ranked_false() throws Exception {
        long matchId = data(start(token, "{\"mode\":\"AI\",\"aiDifficulty\":\"EASY\"}")).get("matchId").asLong();
        result(token, matchId, aiResult("WIN"));
        assertThat(jdbc.queryForObject("SELECT ranked FROM matches WHERE id = ?", Boolean.class, matchId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT ai_wins FROM user_stats WHERE user_id = ?", Integer.class, userId))
                .as("상한을 어긴 결과는 AI 전적에 넣지 않는다").isZero();
    }

    /** 깨지면: AI가 블록을 거의 두지 않은(topout할 수 없는) 판을 승리로 신고해 AI 전적을 부풀릴 수 있다(보안 점검 L-6) */
    @Test
    void AI가_topout할_수_없는_승리는_ranked_false이고_AI_전적에_넣지_않는다() throws Exception {
        long matchId = startAi();
        String forged = aiResult("WIN").replace(
                "\"piecesPlaced\":160,\"attackSent\":21,\"attackReceived\":38", "\"piecesPlaced\":3,\"attackSent\":0,\"attackReceived\":0");
        assertThat(data(result(token, matchId, forged)).get("ranked").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT ai_wins FROM user_stats WHERE user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT result FROM match_players WHERE match_id = ? AND slot = 1", String.class, matchId))
                .as("경기 행은 남긴다").isEqualTo("WIN");
    }

    @Test
    void 최고_점수보다_낮으면_판_수만_오르고_newBest는_false() throws Exception {
        result(token, startSolo(), SOLO_RESULT);
        JsonNode data = data(result(token, startSolo(), SOLO_RESULT.replace("45200", "100")));
        assertThat(data.get("newBest").asBoolean()).isFalse();
        assertThat(jdbc.queryForMap("SELECT solo_games, solo_best_score FROM user_stats WHERE user_id = ?", userId))
                .containsEntry("solo_games", 2).containsEntry("solo_best_score", 45200);
    }

    @Test
    void AI_승패는_승자와_양측_성적과_AI_전적에_반영된다() throws Exception {
        long win = startAi();
        JsonNode data = data(result(token, win, aiResult("WIN")));
        assertThat(data.get("newBest").asBoolean()).isFalse();
        assertThat(data.get("soloRank").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT winner_user_id FROM matches WHERE id = ?", Long.class, win)).isEqualTo(userId);
        assertThat(jdbc.queryForMap("SELECT result, score, attack_sent, attack_received FROM match_players WHERE match_id = ? AND slot = 1", win))
                .containsEntry("result", "WIN").containsEntry("attack_sent", 38).containsEntry("attack_received", 21);
        assertThat(jdbc.queryForMap("SELECT result, score, attack_sent FROM match_players WHERE match_id = ? AND slot = 2", win))
                .containsEntry("result", "LOSE").containsEntry("score", 30100).containsEntry("attack_sent", 21);

        long lose = startAi();
        result(token, lose, aiResult("LOSE"));
        assertThat(jdbc.queryForObject("SELECT winner_user_id FROM matches WHERE id = ?", Long.class, lose)).isNull();
        assertThat(jdbc.queryForMap("SELECT ai_wins, ai_losses, solo_games FROM user_stats WHERE user_id = ?", userId))
                .containsEntry("ai_wins", 1).containsEntry("ai_losses", 1).containsEntry("solo_games", 0);
    }

    @Test
    void 필드_누락_음수_모드와_맞지_않는_필드는_INVALID_RESULT() throws Exception {
        long solo = startSolo();
        expectError(result(token, solo, aiResult("WIN")), 400, "INVALID_RESULT");
        expectError(result(token, solo, SOLO_RESULT.replace("\"durationMs\":184000,", "")), 400, "INVALID_RESULT");
        expectError(result(token, solo, SOLO_RESULT.replace("45200", "-1")), 400, "INVALID_RESULT");
        expectError(result(token, solo, "{"), 400, "INVALID_RESULT");

        long ai = data(start(token, "{\"mode\":\"AI\",\"aiDifficulty\":\"NORMAL\"}")).get("matchId").asLong();
        expectError(result(token, ai, aiResult("DRAW")), 400, "INVALID_RESULT");
        expectError(result(token, ai, SOLO_RESULT), 400, "INVALID_RESULT");
        assertThat(jdbc.queryForObject("SELECT status FROM matches WHERE id = ?", String.class, ai)).isEqualTo("PLAYING");
    }

    @Test
    void 결과_없이_2시간이_지난_싱글_AI_경기는_ABORTED_EXPIRED가_되고_전적은_그대로다() throws Exception {
        long old = startSolo();
        long fresh = startSolo();
        Instant now = Instant.now();
        jdbc.update("UPDATE matches SET started_at = ? WHERE id = ?", Timestamp.from(now.minus(Duration.ofHours(2)).minusSeconds(5)), old);
        jdbc.update("UPDATE matches SET started_at = ? WHERE id = ?", Timestamp.from(now.minus(Duration.ofMinutes(119))), fresh);

        assertThat(matchService.expireUnsaved()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT status, end_reason, ended_at FROM matches WHERE id = ?", old))
                .containsEntry("status", "ABORTED").containsEntry("end_reason", "EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM matches WHERE id = ?", String.class, fresh)).isEqualTo("PLAYING");
        assertThat(jdbc.queryForObject("SELECT solo_games FROM user_stats WHERE user_id = ?", Integer.class, userId)).isZero();
        expectError(result(token, old, SOLO_RESULT), 409, "MATCH_ALREADY_FINISHED");
    }

    /** T-39: 깨지면 봇 의심 결과가 전적·랭킹에 들어가거나 화면이 알림을 띄울 근거(botSuspected)가 사라진다 */
    @Test
    void 키_입력_없이_블록을_놓은_결과는_봇_의심으로_저장만_하고_전적에_넣지_않는다() throws Exception {
        long ai = startAi();
        JsonNode saved = data(result(token, ai, """
                {"result":"WIN","durationMs":120000,"keyPresses":3,
                 "inputStats":{"intervalCv":0.05,"holdMeanMs":10,"holdCv":0.02,"inputsPerPiece":0.05},
                 "player":{"score":3000,"linesCleared":10,"level":2,"piecesPlaced":60,"attackSent":4,"attackReceived":0,"maxCombo":1},
                 "ai":{"score":100,"linesCleared":0,"level":1,"piecesPlaced":40,"attackSent":0,"attackReceived":4,"maxCombo":0}}"""));
        assertThat(saved.get("botSuspected").asBoolean()).isTrue();
        assertThat(saved.get("ranked").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT ai_wins FROM user_stats WHERE user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM matches WHERE id = ?", String.class, ai)).isEqualTo("FINISHED");
        // T-40: 판정 근거값을 경기 행에 남긴다(기준값 조정·소명 검토용)
        assertThat(jdbc.queryForObject("SELECT key_presses FROM matches WHERE id = ?", Integer.class, ai)).isEqualTo(3);
        JsonNode stats = json.readTree(jdbc.queryForObject("SELECT input_stats FROM matches WHERE id = ?", String.class, ai));
        assertThat(stats.get("hard").asBoolean()).isTrue();
        assertThat(stats.get("softSignals").asInt()).isEqualTo(3);
        assertThat(stats.get("intervalCv").asDouble()).isEqualTo(0.05);

        long solo = startSolo();
        JsonNode human = data(result(token, solo, SOLO_RESULT.replace("{\"durationMs\"", "{\"keyPresses\":500,\"durationMs\"")));
        assertThat(human.get("botSuspected").asBoolean()).isFalse();
        assertThat(human.get("ranked").asBoolean()).isTrue();
    }

    /** 시작한 뒤 200초 동안 플레이한 것처럼 시작 시각을 당긴다(상한 ⑤: 플레이 시간 184초 ≤ 서버 경과 + 2초) */
    private long startSolo() throws Exception {
        return played(data(start(token, "{\"mode\":\"SOLO\"}")).get("matchId").asLong());
    }

    private long startAi() throws Exception {
        return played(data(start(token, "{\"mode\":\"AI\",\"aiDifficulty\":\"EASY\"}")).get("matchId").asLong());
    }

    private long played(long matchId) {
        jdbc.update("UPDATE matches SET started_at = ? WHERE id = ?", Timestamp.from(Instant.now().minusSeconds(200)), matchId);
        return matchId;
    }

    private MockHttpServletResponse start(String accessToken, String body) throws Exception {
        return mvc.perform(post("/api/v1/matches").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
    }

    private MockHttpServletResponse result(String accessToken, long matchId, String body) throws Exception {
        return mvc.perform(post("/api/v1/matches/" + matchId + "/result").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
    }

    private JsonNode guest(String nickname) throws Exception {
        return data(mvc.perform(post("/api/v1/auth/guest").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"" + nickname + "\"}")).andReturn().getResponse());
    }

    private JsonNode data(MockHttpServletResponse res) throws Exception {
        return json.readTree(res.getContentAsString(StandardCharsets.UTF_8)).get("data");
    }

    private void expectError(MockHttpServletResponse res, int status, String code) throws Exception {
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(status);
        assertThat(json.readTree(res.getContentAsString()).at("/error/code").asText()).isEqualTo(code);
    }
}
