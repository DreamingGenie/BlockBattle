package com.blockbattle.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.blockbattle.realtime.RoomEventPublisher;
import com.blockbattle.store.redis.RedisKeys;
import com.blockbattle.support.ContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * API 명세 3.12~3.14, FR-ROM-01·02, ENT-003·006~008을 대조한다.
 * 깨지면: 게임 코드 형식·중복 방지, 한 사람 한 방, 정원·상태 검사, 입장 알림·활동 TTL 규칙이 어긋났다는 뜻이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ContainersConfig.class)
class RoomApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @MockitoBean RoomEventPublisher events;
    @MockitoSpyBean RoomCodeGenerator codes;
    @MockitoBean RoomLeaveHook leaveHook;
    @Autowired RoomService roomService;

    record Player(long userId, String token, String nickname) {
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM match_players");
        jdbc.update("DELETE FROM matches");
        jdbc.update("DELETE FROM game_rooms");
        jdbc.update("DELETE FROM user_stats");
        jdbc.update("DELETE FROM users");
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void 방_생성은_허용_문자_6자리_코드와_초대_URL을_주고_WAITING_이력과_30분_방_키를_만든다() throws Exception {
        Player host = guest("오션");
        MockHttpServletResponse res = create(host, "{}");
        assertThat(res.getStatus()).isEqualTo(201);
        JsonNode data = data(res);
        String code = data.get("roomCode").asText();
        assertThat(code).matches("[A-HJ-NP-Z2-9]{6}");
        assertThat(data.get("inviteUrl").asText()).endsWith("/room/" + code);
        assertThat(data.get("status").asText()).isEqualTo("WAITING");
        assertThat(data.get("roundCount").asInt()).isEqualTo(1);
        assertThat(data.get("hostUserId").asLong()).isEqualTo(host.userId());
        OffsetDateTime expiresAt = OffsetDateTime.parse(data.get("expiresAt").asText());
        assertThat(expiresAt.getOffset().getId()).isEqualTo("+09:00");
        assertThat(Duration.between(OffsetDateTime.now(), expiresAt).toMinutes()).isBetween(29L, 30L);

        assertThat(jdbc.queryForMap("SELECT status, round_count, host_user_id, close_reason FROM game_rooms WHERE room_code = ?", code))
                .containsEntry("status", "WAITING").containsEntry("round_count", 1)
                .containsEntry("host_user_id", host.userId()).containsEntry("close_reason", null);
        assertThat(redis.getExpire(RedisKeys.room(code))).isBetween(29 * 60L, 30 * 60L);
        assertThat(redis.opsForValue().get(RedisKeys.userRoom(host.userId()))).isEqualTo(code);
    }

    @Test
    void 라운드_수는_1이나_3만_받는다() throws Exception {
        assertThat(data(create(guest("하나"), "{\"roundCount\":3}")).get("roundCount").asInt()).isEqualTo(3);
        for (String body : List.of("{\"roundCount\":2}", "{\"roundCount\":0}", "{\"roundCount\":\"x\"}")) {
            expectError(create(guest("둘이" + body.length()), body), 400, "INVALID_ROUND_COUNT");
        }
    }

    @Test
    void 코드가_활성_방과_겹치면_다른_코드를_고른다() throws Exception {
        doReturn("ABCDEF").doReturn("ABCDEF").doReturn("GHJKMN").when(codes).next();
        assertThat(data(create(guest("하나"), "{}")).get("roomCode").asText()).isEqualTo("ABCDEF");
        assertThat(data(create(guest("두리"), "{}")).get("roomCode").asText()).isEqualTo("GHJKMN");
    }

    @Test
    void 이미_활성_방에_있으면_새로_만들거나_다른_방에_들어갈_수_없다() throws Exception {
        Player a = guest("하나");
        Player b = guest("두리");
        String roomA = data(create(a, "{}")).get("roomCode").asText();
        String roomB = data(create(b, "{}")).get("roomCode").asText();
        expectError(create(a, "{}"), 409, "ALREADY_IN_ROOM");
        expectError(join(a, roomB), 409, "ALREADY_IN_ROOM");
        assertThat(redis.opsForValue().get(RedisKeys.userRoom(a.userId()))).isEqualTo(roomA);
    }

    @Test
    void 입장_전_조회는_참가자가_아니어도_되고_없는_방은_404() throws Exception {
        Player host = guest("오션");
        String code = data(create(host, "{}")).get("roomCode").asText();
        JsonNode room = data(mvc.perform(get("/api/v1/rooms/" + code).header("Authorization", "Bearer " + guest("손님").token()))
                .andReturn().getResponse());
        assertThat(room.get("status").asText()).isEqualTo("WAITING");
        assertThat(room.get("players")).hasSize(1);
        assertThat(room.get("players").get(0).get("nickname").asText()).isEqualTo("오션");
        assertThat(room.get("players").get(0).get("ready").asBoolean()).isFalse();
        expectError(mvc.perform(get("/api/v1/rooms/ZZZZZZ").header("Authorization", "Bearer " + host.token()))
                .andReturn().getResponse(), 404, "ROOM_NOT_FOUND");
    }

    @Test
    void 입장하면_두_명이_되고_PLAYER_JOINED를_보내며_방_TTL을_30분으로_되돌린다() throws Exception {
        Player host = guest("오션");
        Player guest = guest("민수");
        String code = data(create(host, "{}")).get("roomCode").asText();
        redis.expire(RedisKeys.room(code), Duration.ofMinutes(3));

        JsonNode room = data(join(guest, code));
        assertThat(room.get("players")).hasSize(2);
        assertThat(room.get("players").get(0).get("userId").asLong()).as("방장이 먼저").isEqualTo(host.userId());
        assertThat(room.get("players").get(1).get("userId").asLong()).isEqualTo(guest.userId());
        assertThat(redis.getExpire(RedisKeys.room(code))).isBetween(29 * 60L, 30 * 60L);
        assertThat(redis.opsForValue().get(RedisKeys.userRoom(guest.userId()))).isEqualTo(code);
        verify(events).publish(eq(code), eq("PLAYER_JOINED"),
                eq(Map.of("userId", guest.userId(), "nickname", "민수", "tag", room.get("players").get(1).get("tag").asText())));

        // 이미 참가자면 같은 응답이고 다시 알리지 않는다
        assertThat(data(join(guest, code)).get("players")).hasSize(2);
        verify(events, times(1)).publish(eq(code), eq("PLAYER_JOINED"), any());
    }

    @Test
    void 정원이_찼거나_WAITING이_아니거나_없는_방이면_입장을_거부한다() throws Exception {
        Player host = guest("오션");
        String code = data(create(host, "{}")).get("roomCode").asText();
        join(guest("민수"), code);
        Player third = guest("지아");
        expectError(join(third, code), 409, "ROOM_FULL");
        assertThat(redis.hasKey(RedisKeys.userRoom(third.userId()))).as("거부되면 활성 방에 묶이지 않는다").isFalse();

        String playing = data(create(guest("태오"), "{}")).get("roomCode").asText();
        redis.opsForHash().put(RedisKeys.room(playing), "status", "PLAYING");
        expectError(join(third, playing), 409, "ROOM_ALREADY_PLAYING");
        expectError(join(third, "ZZZZZZ"), 404, "ROOM_NOT_FOUND");
        verify(events, never()).publish(eq(playing), any(), any());
    }

    @Test
    void 동시에_여러_명이_들어와도_정원_2명을_넘지_않는다() throws Exception {
        Player host = guest("오션");
        String code = data(create(host, "{}")).get("roomCode").asText();
        List<Player> joiners = new ArrayList<>();
        for (int i = 0; i < 6; i++) joiners.add(guest("손님" + i));
        List<Callable<Integer>> calls = new ArrayList<>();
        for (Player p : joiners) calls.add(() -> join(p, code).getStatus());
        var pool = Executors.newFixedThreadPool(6);
        List<Integer> statuses = new ArrayList<>();
        for (var f : pool.invokeAll(calls)) statuses.add(f.get());
        pool.shutdown();
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(5);
        assertThat(redis.opsForHash().size(RedisKeys.roomPlayers(code))).isEqualTo(2);
    }

    @Test
    void 일반_참가자가_나가면_204로_빠지고_PLAYER_LEFT를_보내며_TTL을_되돌린다() throws Exception {
        Player host = guest("오션");
        Player guest = guest("민수");
        String code = data(create(host, "{}")).get("roomCode").asText();
        join(guest, code);
        redis.expire(RedisKeys.room(code), Duration.ofMinutes(3));

        MockHttpServletResponse res = leave(guest, code);
        assertThat(res.getStatus()).isEqualTo(204);
        assertThat(redis.opsForHash().hasKey(RedisKeys.roomPlayers(code), Long.toString(guest.userId()))).isFalse();
        assertThat(redis.hasKey(RedisKeys.userRoom(guest.userId()))).isFalse();
        assertThat(redis.getExpire(RedisKeys.room(code))).isBetween(29 * 60L, 30 * 60L);
        verify(events).publish(code, "PLAYER_LEFT", Map.of("userId", guest.userId(), "reason", "LEFT"));
        assertThat(jdbc.queryForObject("SELECT status FROM game_rooms WHERE room_code = ?", String.class, code)).isEqualTo("WAITING");
        // 나간 사람은 다른 방을 만들 수 있다
        assertThat(create(guest, "{}").getStatus()).isEqualTo(201);
    }

    @Test
    void 방장이_나가면_방을_닫고_ROOM_CLOSED_HOST_LEFT를_보낸다() throws Exception {
        Player host = guest("오션");
        Player guest = guest("민수");
        String code = data(create(host, "{}")).get("roomCode").asText();
        join(guest, code);

        assertThat(leave(host, code).getStatus()).isEqualTo(204);
        assertThat(jdbc.queryForMap("SELECT status, close_reason, closed_at FROM game_rooms WHERE room_code = ?", code))
                .containsEntry("status", "CLOSED").containsEntry("close_reason", "HOST_LEFT");
        assertThat(redis.hasKey(RedisKeys.room(code))).isFalse();
        assertThat(redis.hasKey(RedisKeys.userRoom(guest.userId()))).as("남은 참가자도 풀린다").isFalse();
        verify(events).publish(code, "ROOM_CLOSED", Map.of("reason", "HOST_LEFT"));
        expectError(mvc.perform(get("/api/v1/rooms/" + code).header("Authorization", "Bearer " + guest.token()))
                .andReturn().getResponse(), 404, "ROOM_NOT_FOUND");
    }

    @Test
    void 참가자가_아니면_403_없는_방이면_404() throws Exception {
        String code = data(create(guest("오션"), "{}")).get("roomCode").asText();
        expectError(leave(guest("손님"), code), 403, "NOT_PARTICIPANT");
        expectError(leave(guest("손님2"), "ZZZZZZ"), 404, "ROOM_NOT_FOUND");
    }

    @Test
    void 경기_중_퇴장은_RoomLeaveHook을_방_경기_사용자로_부른_뒤_퇴장한다() throws Exception {
        Player host = guest("오션");
        Player guest = guest("민수");
        String code = data(create(host, "{}")).get("roomCode").asText();
        join(guest, code);
        redis.opsForHash().putAll(RedisKeys.room(code), Map.of("status", "PLAYING", "currentMatchId", "777"));

        assertThat(leave(guest, code).getStatus()).isEqualTo(204);
        verify(leaveHook).onLeaveDuringMatch(code, 777L, guest.userId());
    }

    @Test
    void Redis_방_키가_사라진_열린_방은_EXPIRED로_닫고_진행_경기를_ABORTED로_끝낸다() throws Exception {
        Player host = guest("오션");
        String code = data(create(host, "{}")).get("roomCode").asText();
        long roomId = jdbc.queryForObject("SELECT id FROM game_rooms WHERE room_code = ?", Long.class, code);
        jdbc.update("""
                INSERT INTO matches (mode, room_id, series_no, round_no, attempt_no, seed, status, started_at)
                VALUES ('FRIEND', ?, 1, 1, 1, 7, 'PLAYING', NOW(6))""", roomId);
        String alive = data(create(guest("민수"), "{}")).get("roomCode").asText();
        redis.delete(RedisKeys.room(code)); // TTL 만료와 같은 상태

        assertThat(roomService.expireRooms()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT status, close_reason, closed_at FROM game_rooms WHERE id = ?", roomId))
                .containsEntry("status", "CLOSED").containsEntry("close_reason", "EXPIRED");
        assertThat(jdbc.queryForMap("SELECT status, end_reason FROM matches WHERE room_id = ?", roomId))
                .containsEntry("status", "ABORTED").containsEntry("end_reason", "EXPIRED");
        verify(events).publish(code, "ROOM_CLOSED", Map.of("reason", "EXPIRED"));
        assertThat(jdbc.queryForObject("SELECT status FROM game_rooms WHERE room_code = ?", String.class, alive)).isEqualTo("WAITING");
        assertThat(roomService.expireRooms()).as("이미 닫힌 방은 다시 닫지 않는다").isZero();
    }

    private MockHttpServletResponse leave(Player p, String code) throws Exception {
        return mvc.perform(post("/api/v1/rooms/" + code + "/leave").header("Authorization", "Bearer " + p.token()))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse create(Player p, String body) throws Exception {
        return mvc.perform(post("/api/v1/rooms").header("Authorization", "Bearer " + p.token())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
    }

    private MockHttpServletResponse join(Player p, String code) throws Exception {
        return mvc.perform(post("/api/v1/rooms/" + code + "/join").header("Authorization", "Bearer " + p.token()))
                .andReturn().getResponse();
    }

    private Player guest(String nickname) throws Exception {
        String safe = nickname.replaceAll("[^가-힣A-Za-z0-9]", "");
        JsonNode data = data(mvc.perform(post("/api/v1/auth/guest").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"" + safe + "\"}")).andReturn().getResponse());
        return new Player(data.get("userId").asLong(), data.get("accessToken").asText(), safe);
    }

    private JsonNode data(MockHttpServletResponse res) throws Exception {
        return json.readTree(res.getContentAsString(StandardCharsets.UTF_8)).get("data");
    }

    private void expectError(MockHttpServletResponse res, int status, String code) throws Exception {
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(status);
        assertThat(json.readTree(res.getContentAsString()).at("/error/code").asText()).isEqualTo(code);
    }
}
