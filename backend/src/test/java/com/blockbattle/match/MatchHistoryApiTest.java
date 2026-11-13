package com.blockbattle.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.blockbattle.support.ContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * API 명세 3.7·3.10(API-007·API-016), REQ-015, POL-012.
 * 깨지면: 남의 경기·진행 중 경기가 목록에 섞이거나, 정렬·페이지·필터 계약이 어긋나거나, 비참가자가 상세를 보거나, APM 계산식이 바뀌었다는 뜻이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ContainersConfig.class)
class MatchHistoryApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    record Player(long userId, String token) {
    }

    Player a;
    Player b;
    Player c;
    final Instant base = Instant.parse("2026-11-10T03:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM match_players");
        jdbc.update("DELETE FROM matches");
        jdbc.update("DELETE FROM game_rooms");
        jdbc.update("DELETE FROM user_stats");
        jdbc.update("DELETE FROM users");
        a = guest("오션");
        b = guest("민수");
        c = guest("지아");
    }

    @Test
    void 목록은_내가_참가한_끝난_경기만_최신순으로_주고_모드_필터와_페이지를_지킨다() throws Exception {
        long aborted = soloMatch(a.userId(), "ABORTED", 0, 0);
        long solo = soloMatch(a.userId(), "FINISHED", 10, 500);
        long ai = aiMatch(a.userId(), 20);
        long friend = friendMatch(30, 184_000, 38);
        soloMatch(a.userId(), "PLAYING", 40, 0); // 진행 중은 빠진다
        soloMatch(c.userId(), "FINISHED", 50, 900); // 남의 경기도 빠진다

        JsonNode data = list(a, "").get("data");
        assertThat(data.get("totalElements").asLong()).isEqualTo(4);
        assertThat(data.get("page").asInt()).isZero();
        assertThat(data.get("size").asInt()).isEqualTo(20);
        assertThat(ids(data)).containsExactly(friend, ai, solo, aborted);

        JsonNode f = data.get("items").get(0);
        assertThat(f.get("mode").asText()).isEqualTo("FRIEND");
        assertThat(f.get("endReason").asText()).isEqualTo("TOP_OUT");
        assertThat(f.get("startedAt").asText()).isEqualTo("2026-11-10T12:00:30+09:00");
        assertThat(f.get("durationMs").asInt()).isEqualTo(184_000);
        assertThat(f.get("myResult").asText()).isEqualTo("WIN");
        assertThat(f.at("/opponent/userId").asLong()).isEqualTo(b.userId());
        assertThat(f.at("/opponent/nickname").asText()).isEqualTo("민수");
        assertThat(f.at("/opponent/playerType").asText()).isEqualTo("USER");
        assertThat(f.at("/opponent/aiDifficulty").isNull()).isTrue();
        JsonNode aiItem = data.get("items").get(1);
        assertThat(aiItem.at("/opponent/playerType").asText()).isEqualTo("AI");
        assertThat(aiItem.at("/opponent/userId").isNull()).isTrue();
        assertThat(aiItem.at("/opponent/aiDifficulty").asText()).isEqualTo("HARD");
        assertThat(data.get("items").get(2).get("opponent").isNull()).as("싱글은 상대가 없다").isTrue();
        assertThat(data.get("items").get(2).get("myScore").asInt()).isEqualTo(500);

        assertThat(ids(list(a, "?mode=AI").get("data"))).containsExactly(ai);
        JsonNode page1 = list(a, "?size=2&page=1").get("data");
        assertThat(ids(page1)).containsExactly(solo, aborted);
        assertThat(page1.get("totalElements").asLong()).isEqualTo(4);
        assertThat(ids(list(b, "").get("data"))).as("상대도 자기 목록에서 본다").containsExactly(friend);
    }

    @Test
    void 잘못된_모드_페이지_크기는_INVALID_QUERY다() throws Exception {
        for (String q : new String[] {"?mode=RANKED", "?size=0", "?size=51", "?page=-1", "?page=abc"}) {
            MvcResult r = mvc.perform(get("/api/v1/users/me/matches" + q).header("Authorization", "Bearer " + a.token())).andReturn();
            assertThat(r.getResponse().getStatus()).as(q).isEqualTo(400);
            assertThat(json.readTree(r.getResponse().getContentAsString()).at("/error/code").asText()).as(q).isEqualTo("INVALID_QUERY");
        }
    }

    @Test
    void 상세는_참가자만_보고_APM은_저장된_공격과_시간으로_계산한다() throws Exception {
        long friend = friendMatch(30, 184_000, 38);
        JsonNode d = detail(a, friend, 200).get("data");
        assertThat(d.get("mode").asText()).isEqualTo("FRIEND");
        assertThat(d.get("status").asText()).isEqualTo("FINISHED");
        assertThat(d.get("winnerUserId").asLong()).isEqualTo(a.userId());
        assertThat(d.at("/room/roomCode").asText()).isEqualTo("K7Q2MX");
        assertThat(d.at("/room/roundNo").asInt()).isEqualTo(2);
        assertThat(d.get("endedAt").asText()).isEqualTo("2026-11-10T12:03:34+09:00");
        JsonNode p1 = d.get("players").get(0);
        assertThat(p1.get("slot").asInt()).isEqualTo(1);
        assertThat(p1.get("userId").asLong()).isEqualTo(a.userId());
        assertThat(p1.get("attackSent").asInt()).isEqualTo(38);
        assertThat(p1.get("apm").asDouble()).as("38줄 ÷ 184초(3.0667분) = 12.39 → 12.4").isEqualTo(12.4);
        assertThat(d.get("players").get(1).get("apm").asDouble()).as("21줄 ÷ 3.0667분 = 6.85 → 6.8").isEqualTo(6.8);

        long solo = soloMatch(a.userId(), "FINISHED", 0, 100);
        assertThat(detail(a, solo, 200).at("/data/room").isNull()).as("FRIEND가 아니면 room은 null").isTrue();
        assertThat(detail(c, friend, 403).at("/error/code").asText()).isEqualTo("NOT_PARTICIPANT");
        assertThat(detail(a, 999_999, 404).at("/error/code").asText()).isEqualTo("MATCH_NOT_FOUND");
    }

    private long soloMatch(long userId, String status, int offsetSec, int score) {
        long id = insertMatch("SOLO", null, null, status, status.equals("ABORTED") ? "EXPIRED" : status.equals("FINISHED") ? "COMPLETED" : null,
                offsetSec, status.equals("PLAYING") ? null : 60_000, null);
        jdbc.update("INSERT INTO match_players (match_id, slot, user_id, player_type, result, score) VALUES (?, 1, ?, 'USER', 'NONE', ?)",
                id, userId, score);
        return id;
    }

    private long aiMatch(long userId, int offsetSec) {
        long id = insertMatch("AI", "HARD", null, "FINISHED", "COMPLETED", offsetSec, 90_000, userId);
        jdbc.update("INSERT INTO match_players (match_id, slot, user_id, player_type, result) VALUES (?, 1, ?, 'USER', 'WIN')", id, userId);
        jdbc.update("INSERT INTO match_players (match_id, slot, user_id, player_type, result) VALUES (?, 2, NULL, 'AI', 'LOSE')", id);
        return id;
    }

    /** a(1번 자리, 승, 공격 38) 대 b(2번 자리, 패, 공격 21) */
    private long friendMatch(int offsetSec, int durationMs, int attackSent) {
        jdbc.update("INSERT INTO game_rooms (room_code, host_user_id, status, round_count, created_at) VALUES ('K7Q2MX', ?, 'FINISHED', 3, ?)",
                a.userId(), Timestamp.from(base));
        Long roomId = jdbc.queryForObject("SELECT MAX(id) FROM game_rooms", Long.class);
        long id = insertMatch("FRIEND", null, roomId, "FINISHED", "TOP_OUT", offsetSec, durationMs, a.userId());
        jdbc.update("""
                INSERT INTO match_players (match_id, slot, user_id, player_type, result, score, lines_cleared, level, pieces_placed,
                    attack_sent, attack_received, max_combo) VALUES (?, 1, ?, 'USER', 'WIN', 12400, 41, 5, 120, ?, 21, 5)""",
                id, a.userId(), attackSent);
        jdbc.update("""
                INSERT INTO match_players (match_id, slot, user_id, player_type, result, score, attack_sent, attack_received)
                VALUES (?, 2, ?, 'USER', 'LOSE', 9800, 21, ?)""", id, b.userId(), attackSent);
        return id;
    }

    private long insertMatch(String mode, String ai, Long roomId, String status, String endReason, int offsetSec,
                             Integer durationMs, Long winner) {
        Instant started = base.plusSeconds(offsetSec);
        Instant ended = durationMs == null ? null : started.plusMillis(durationMs);
        boolean friend = roomId != null;
        jdbc.update("""
                INSERT INTO matches (mode, room_id, series_no, round_no, attempt_no, ai_difficulty, seed, status, end_reason,
                    winner_user_id, started_at, ended_at, duration_ms) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?)""",
                mode, roomId, friend ? 1 : null, friend ? 2 : null, friend ? 1 : null, ai, status, endReason, winner,
                Timestamp.from(started), ended == null ? null : Timestamp.from(ended), durationMs);
        return jdbc.queryForObject("SELECT MAX(id) FROM matches", Long.class);
    }

    private JsonNode list(Player p, String query) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/users/me/matches" + query).header("Authorization", "Bearer " + p.token())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode detail(Player p, long matchId, int status) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/matches/" + matchId).header("Authorization", "Bearer " + p.token())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(status);
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static java.util.List<Long> ids(JsonNode data) {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        data.get("items").forEach(i -> ids.add(i.get("matchId").asLong()));
        return ids;
    }

    private Player guest(String nickname) throws Exception {
        JsonNode data = json.readTree(mvc.perform(post("/api/v1/auth/guest").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"" + nickname + "\"}")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
        return new Player(data.get("userId").asLong(), data.get("accessToken").asText());
    }
}
