package com.blockbattle.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.blockbattle.store.redis.RedisKeys;
import com.blockbattle.support.ContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * API 명세 3.11, BR-08(POL-008), ENT-011·012를 대조한다.
 * 깨지면: 랭킹 정렬(동점은 먼저 달성한 사람이 위)·제외 규칙이나 Redis가 비었을 때 MySQL 원본으로 같은 순위를 만드는 규칙이 어긋났다는 뜻이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ContainersConfig.class)
class RankingApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    record Player(long userId, String token) {
    }

    Player early;   // 5,000점, 가장 먼저 달성
    Player late;    // 5,000점, 나중에 달성
    Player top;     // 9,000점
    Player none;    // 기록 없음

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM match_players");
        jdbc.update("DELETE FROM matches");
        jdbc.update("DELETE FROM user_stats");
        jdbc.update("DELETE FROM users");
        flushRedis();
        early = guest("일찍");
        late = guest("늦게");
        top = guest("최고");
        none = guest("없음");
        Instant base = Instant.parse("2026-11-01T00:00:00Z");
        best(early, 5_000, base);
        best(late, 5_000, base.plusSeconds(60));
        best(top, 9_000, base.plusSeconds(120));
    }

    @Test
    void 최고_점수_순이고_동점은_먼저_달성한_사람이_위이며_기록_없는_사용자는_빠진다() throws Exception {
        JsonNode data = ranking(late, "");
        assertThat(ids(data.get("top"))).containsExactly(top.userId(), early.userId(), late.userId());
        JsonNode first = data.get("top").get(0);
        assertThat(first.get("rank").asInt()).isEqualTo(1);
        assertThat(first.get("nickname").asText()).isEqualTo("최고");
        assertThat(first.get("tag").asText()).matches("\\d{4}");
        assertThat(first.get("score").asLong()).isEqualTo(9_000);
        assertThat(first.get("achievedAt").asText()).isEqualTo("2026-11-01T09:02:00+09:00");
        assertThat(data.get("me").get("rank").asInt()).isEqualTo(3);
        assertThat(data.get("me").get("score").asLong()).isEqualTo(5_000);

        assertThat(ranking(none, "").get("me").isNull()).isTrue();
    }

    @Test
    void Redis가_비면_MySQL_원본으로_같은_순위를_다시_만든다() throws Exception {
        List<Long> before = ids(ranking(early, "").get("top"));
        assertThat(redis.opsForValue().get(RedisKeys.RANKING_SOLO_SEQ)).as("복구 순번 뒤로 카운터를 맞춘다").isEqualTo("3");

        // 복구 뒤 새로 5,000점을 달성한 사람은 같은 점수의 기존 두 사람보다 아래
        Player newcomer = guest("새내기");
        long matchId = data(mvc.perform(post("/api/v1/matches").header("Authorization", "Bearer " + newcomer.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"SOLO\"}")).andReturn().getResponse()).get("matchId").asLong();
        // 184초 플레이를 실제로 한 것처럼 시작 시각을 당긴다(T-30 상한 ⑤: 플레이 시간 ≤ 서버 경과 + 2초)
        jdbc.update("UPDATE matches SET started_at = ? WHERE id = ?", Timestamp.from(Instant.now().minusSeconds(200)), matchId);
        JsonNode saved = data(mvc.perform(post("/api/v1/matches/" + matchId + "/result")
                .header("Authorization", "Bearer " + newcomer.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"durationMs\":184000,\"player\":{\"score\":5000,\"linesCleared\":62,\"level\":7,\"piecesPlaced\":171,\"maxCombo\":5}}"))
                .andReturn().getResponse());
        assertThat(saved.get("soloRank").asLong()).isEqualTo(4);

        List<Long> live = ids(ranking(early, "").get("top"));
        assertThat(live).containsExactly(top.userId(), early.userId(), late.userId(), newcomer.userId());
        assertThat(live.subList(0, 3)).isEqualTo(before);

        flushRedis();
        assertThat(ids(ranking(early, "").get("top"))).isEqualTo(live);
        assertThat(ranking(newcomer, "").get("me").get("rank").asInt()).isEqualTo(4);
    }

    @Test
    void limit만큼만_주고_기본은_10이다() throws Exception {
        assertThat(ranking(early, "?limit=2").get("top")).hasSize(2);
        assertThat(ranking(early, "?limit=50").get("top")).hasSize(3);
        assertThat(ranking(early, "").get("top")).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "51", "-1", "abc"})
    void limit이_1부터_50이_아니면_INVALID_QUERY(String limit) throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/api/v1/rankings/solo?limit=" + limit)
                .header("Authorization", "Bearer " + early.token())).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(json.readTree(res.getContentAsString()).at("/error/code").asText()).isEqualTo("INVALID_QUERY");
    }

    /** T-35: 깨지면 AI 랭킹이 최단 승리 시간 순이 아니거나, 진 경기·ranked=false·신고 시간 축소 경기가 섞인다는 뜻이다 */
    @Test
    void AI_랭킹은_난이도별_최단_승리_시간_순이고_사용자당_한_판이다() throws Exception {
        Instant base = Instant.parse("2026-11-02T00:00:00Z");
        aiMatch(early, "HARD", 90_000, true, true, base, 90_000);
        aiMatch(early, "HARD", 70_000, true, true, base.plusSeconds(600), 70_000); // early의 최단
        aiMatch(late, "HARD", 70_000, true, true, base.plusSeconds(900), 70_000);  // 같은 시간, 나중 달성
        aiMatch(top, "HARD", 30_000, false, true, base, 30_000);                   // 진 경기
        aiMatch(top, "HARD", 31_000, true, false, base, 31_000);                   // ranked=false
        aiMatch(top, "HARD", 32_000, true, true, base, 60_000);                    // 실제 60초를 32초로 신고
        aiMatch(top, "HARD", 34_000, true, true, base, 38_000);                    // 4초 차이는 허용
        aiMatch(none, "EASY", 10_000, true, true, base, 10_000);                   // 다른 난이도

        JsonNode data = aiRanking(late, "HARD", "");
        assertThat(data.get("difficulty").asText()).isEqualTo("HARD");
        assertThat(ids(data.get("top"))).containsExactly(top.userId(), early.userId(), late.userId());
        assertThat(data.get("top").get(0).get("durationMs").asLong()).isEqualTo(34_000);
        assertThat(data.get("top").get(1).get("durationMs").asLong()).isEqualTo(70_000);
        assertThat(data.get("me").get("rank").asInt()).isEqualTo(3);

        JsonNode limited = aiRanking(late, "HARD", "&limit=1");
        assertThat(limited.get("top")).hasSize(1);
        assertThat(limited.get("me").get("rank").asInt()).as("상위 밖이어도 내 순위는 준다").isEqualTo(3);
        assertThat(aiRanking(none, "HARD", "").get("me").isNull()).isTrue();
        assertThat(ids(aiRanking(none, "IMPOSSIBLE", "").get("top"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?difficulty=GOD", "?difficulty=HARD&limit=0"})
    void AI_랭킹_난이도가_없거나_틀리면_INVALID_QUERY(String query) throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/api/v1/rankings/ai" + query)
                .header("Authorization", "Bearer " + early.token())).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(json.readTree(res.getContentAsString()).at("/error/code").asText()).isEqualTo("INVALID_QUERY");
    }

    private void aiMatch(Player player, String difficulty, int durationMs, boolean win, boolean ranked, Instant endedAt, long serverMs) {
        Instant startedAt = endedAt.minusMillis(serverMs);
        jdbc.update("""
                INSERT INTO matches (mode, ai_difficulty, seed, status, end_reason, winner_user_id, ranked, started_at, ended_at, duration_ms)
                VALUES ('AI', ?, 1, 'FINISHED', 'TOP_OUT', ?, ?, ?, ?, ?)""",
                difficulty, win ? player.userId() : null, ranked, Timestamp.from(startedAt), Timestamp.from(endedAt), durationMs);
    }

    private JsonNode aiRanking(Player as, String difficulty, String extra) throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/api/v1/rankings/ai?difficulty=" + difficulty + extra)
                .header("Authorization", "Bearer " + as.token())).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        return data(res);
    }

    private void best(Player player, int score, Instant achievedAt) {
        jdbc.update("UPDATE user_stats SET solo_games = 1, solo_best_score = ?, solo_best_achieved_at = ? WHERE user_id = ?",
                score, Timestamp.from(achievedAt), player.userId());
    }

    private void flushRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    private JsonNode ranking(Player as, String query) throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/api/v1/rankings/solo" + query)
                .header("Authorization", "Bearer " + as.token())).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        return data(res);
    }

    private static List<Long> ids(JsonNode top) {
        List<Long> ids = new ArrayList<>();
        top.forEach(entry -> ids.add(entry.get("userId").asLong()));
        return ids;
    }

    private Player guest(String nickname) throws Exception {
        JsonNode data = data(mvc.perform(post("/api/v1/auth/guest").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"" + nickname + "\"}")).andReturn().getResponse());
        return new Player(data.get("userId").asLong(), data.get("accessToken").asText());
    }

    private JsonNode data(MockHttpServletResponse res) throws Exception {
        return json.readTree(res.getContentAsString(StandardCharsets.UTF_8)).get("data");
    }
}
