package com.blockbattle.battle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.blockbattle.engine.Board;
import com.blockbattle.support.ContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * BR-03(시리즈·라운드)·POL-025(시리즈 중 끊김)·REQ-014(재대결), API 명세 5.4·5.5를 실제 STOMP로 확인한다.
 * 깨지면: 시리즈 승자·다음 라운드·재시도 번호가 틀리거나, 재대결이 한쪽 수락만으로 시작되거나, 5초 라운드 간격이 어긋났다는 뜻이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(ContainersConfig.class)
class FriendSeriesTest {

    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PlayerStateRepository states;

    WebSocketStompClient client;
    final List<StompSession> sessions = Collections.synchronizedList(new ArrayList<>());

    record Player(long userId, String token) {
    }

    Player h;
    Player g;
    long host;
    long guest;
    String code;
    long matchId;
    StompSession hostSession;
    StompSession guestSession;
    BlockingQueue<JsonNode> topic;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM match_players");
        jdbc.update("DELETE FROM matches");
        jdbc.update("DELETE FROM game_rooms");
        jdbc.update("DELETE FROM user_stats");
        jdbc.update("DELETE FROM users");
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @AfterEach
    void tearDown() {
        sessions.forEach(s -> { if (s.isConnected()) s.disconnect(); });
        client.stop();
    }

    /** roundCount 방을 만들고 두 사람이 준비해 첫 라운드 COUNTDOWN까지 받는다 */
    private void startRoom(int roundCount) throws Exception {
        h = guest("오션");
        g = guest("민수");
        host = h.userId();
        guest = g.userId();
        code = json.readTree(mvc.perform(post("/api/v1/rooms").header("Authorization", "Bearer " + h.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"roundCount\":" + roundCount + "}")).andReturn()
                .getResponse().getContentAsString()).at("/data/roomCode").asText();
        mvc.perform(post("/api/v1/rooms/" + code + "/join").header("Authorization", "Bearer " + g.token()));
        hostSession = connect(h);
        topic = subscribe(hostSession, "/topic/rooms/" + code);
        guestSession = connect(g);
        hostSession.send("/app/rooms/" + code + "/ready", Map.of("ready", true));
        guestSession.send("/app/rooms/" + code + "/ready", Map.of("ready", true));
        matchId = next(topic, "COUNTDOWN").at("/payload/matchId").asLong();
    }

    @Test
    void 삼판_방은_먼저_2승한_쪽이_시리즈_승자이고_라운드_사이는_5초다() throws Exception {
        startRoom(3);
        long first = matchId;
        topout(guestSession, guest);
        JsonNode over1 = next(topic, "GAME_OVER").get("payload");
        assertThat(over1.get("winnerUserId").asLong()).isEqualTo(host);
        assertThat(over1.get("seriesWinnerUserId").isNull()).isTrue();
        assertThat(over1.at("/seriesWins/" + host).asInt()).isEqualTo(1);
        long nextStartsAt = over1.get("nextRoundStartsAt").asLong();

        JsonNode cd = next(topic, "COUNTDOWN", 6000);
        JsonNode p = cd.get("payload");
        assertThat(p.get("seriesNo").asInt()).isEqualTo(1);
        assertThat(p.get("roundNo").asInt()).isEqualTo(2);
        assertThat(p.get("attemptNo").asInt()).isEqualTo(1);
        assertThat(p.get("startsAt").asLong()).as("GAME_OVER가 알린 시각에 시작").isEqualTo(nextStartsAt);
        assertThat(p.get("startsAt").asLong() - cd.get("sentAt").asLong()).isEqualTo(3000);
        long endedAt = jdbc.queryForObject("SELECT ended_at FROM matches WHERE id = ?", Timestamp.class, first).getTime();
        assertThat(nextStartsAt - endedAt).as("라운드 사이 5초(BR-03)").isBetween(4990L, 5010L);
        matchId = p.get("matchId").asLong();
        assertThat(matchId).isNotEqualTo(first);

        topout(guestSession, guest);
        JsonNode over2 = next(topic, "GAME_OVER").get("payload");
        assertThat(over2.get("seriesWinnerUserId").asLong()).isEqualTo(host);
        assertThat(over2.get("nextRoundStartsAt").isNull()).isTrue();
        assertThat(over2.at("/seriesWins/" + host).asInt()).isEqualTo(2);
        assertThat(seriesWinner(matchId)).isEqualTo(host);
        assertThat(seriesWinner(first)).isNull();
        assertThat(roomStatus()).isEqualTo("FINISHED");
        assertThat(poll(topic, "COUNTDOWN", 3000)).as("시리즈가 끝나면 다음 라운드는 없다").isNull();
    }

    @Test
    void 무승부는_승수에_넣지_않고_같은_라운드를_시도_번호를_올려_다시_한다() throws Exception {
        startRoom(3);
        long first = matchId;
        states.save(matchId, host, blockedState());
        states.save(matchId, guest, blockedState());
        hostSession.send("/app/rooms/" + code + "/topout", Map.of("seq", 0));
        guestSession.send("/app/rooms/" + code + "/topout", Map.of("seq", 0));
        JsonNode over = next(topic, "GAME_OVER").get("payload");
        assertThat(over.get("reason").asText()).isEqualTo("DRAW");
        assertThat(over.at("/seriesWins/" + host).asInt() + over.at("/seriesWins/" + guest).asInt()).isZero();

        JsonNode p = next(topic, "COUNTDOWN", 6000).get("payload");
        assertThat(p.get("roundNo").asInt()).isEqualTo(1);
        assertThat(p.get("attemptNo").asInt()).isEqualTo(2);
        long retry = p.get("matchId").asLong();
        assertThat(retry).isNotEqualTo(first);
        Map<String, Object> row = jdbc.queryForMap("SELECT round_no, attempt_no, seed FROM matches WHERE id = ?", retry);
        assertThat(((Number) row.get("round_no")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("attempt_no")).intValue()).isEqualTo(2);
        assertThat(((Number) row.get("seed")).longValue()).isEqualTo(p.get("seed").asLong());
    }

    @Test
    void 라운드_사이에_나가면_남은_쪽이_시리즈_승자이고_다음_라운드는_없다() throws Exception {
        startRoom(3);
        topout(guestSession, guest); // 1라운드 방장 승
        next(topic, "GAME_OVER");
        mvc.perform(post("/api/v1/rooms/" + code + "/leave").header("Authorization", "Bearer " + g.token()));
        assertThat(next(topic, "PLAYER_LEFT").at("/payload/reason").asText()).isEqualTo("LEFT");
        assertThat(poll(topic, "COUNTDOWN", 5000)).isNull();
        assertThat(seriesWinner(matchId)).isEqualTo(host);
        assertThat(roomStatus()).isEqualTo("FINISHED");
    }

    @Test
    void 삼판_방에서_끊긴_채_다음_카운트다운까지_돌아오지_않으면_남은_쪽이_시리즈_승자다() throws Exception {
        startRoom(3);
        guestSession.disconnect();
        JsonNode over = poll(topic, "GAME_OVER", 12_000);
        assertThat(over).isNotNull();
        assertThat(over.at("/payload/reason").asText()).isEqualTo("DISCONNECT");
        assertThat(over.at("/payload/seriesWinnerUserId").isNull()).as("1승으로는 시리즈가 끝나지 않는다").isTrue();
        JsonNode again = poll(topic, "GAME_OVER", 4000);
        assertThat(again).as("다음 카운트다운 시각에 남은 쪽 시리즈 승자를 다시 알린다").isNotNull();
        assertThat(again.at("/payload/seriesWinnerUserId").asLong()).isEqualTo(host);
        assertThat(again.at("/payload/nextRoundStartsAt").isNull()).isTrue();
        assertThat(seriesWinner(matchId)).isEqualTo(host);
        assertThat(roomStatus()).isEqualTo("FINISHED");
        assertThat(poll(topic, "COUNTDOWN", 500)).isNull();
    }

    @Test
    void 두_사람이_모두_수락해야_시리즈_번호를_올려_재대결한다() throws Exception {
        startRoom(1);
        topout(guestSession, guest);
        assertThat(next(topic, "GAME_OVER").at("/payload/seriesWinnerUserId").asLong()).isEqualTo(host);
        assertThat(roomStatus()).isEqualTo("FINISHED");

        hostSession.send("/app/rooms/" + code + "/rematch", Map.of("accept", true));
        JsonNode changed = next(topic, "REMATCH_CHANGED").get("payload");
        assertThat(changed.get("userId").asLong()).isEqualTo(host);
        assertThat(changed.get("accept").asBoolean()).isTrue();
        assertThat(poll(topic, "COUNTDOWN", 500)).as("한쪽 수락만으로는 시작하지 않는다").isNull();

        guestSession.send("/app/rooms/" + code + "/rematch", Map.of("accept", true));
        JsonNode p = next(topic, "COUNTDOWN").get("payload");
        assertThat(p.get("seriesNo").asInt()).isEqualTo(2);
        assertThat(p.get("roundNo").asInt()).isEqualTo(1);
        assertThat(p.get("attemptNo").asInt()).isEqualTo(1);
        assertThat(roomStatus()).isEqualTo("PLAYING");
        matchId = p.get("matchId").asLong();
        assertThat(jdbc.queryForObject("SELECT series_no FROM matches WHERE id = ?", Integer.class, matchId)).isEqualTo(2);

        topout(hostSession, host); // 새 시리즈는 승수 0부터
        JsonNode over = next(topic, "GAME_OVER").get("payload");
        assertThat(over.at("/seriesWins/" + guest).asInt()).isEqualTo(1);
        assertThat(over.at("/seriesWins/" + host).asInt()).isZero();
    }

    @Test
    void 한쪽이_거절하면_재대결은_시작되지_않고_방은_FINISHED로_남는다() throws Exception {
        startRoom(1);
        topout(guestSession, guest);
        next(topic, "GAME_OVER");
        guestSession.send("/app/rooms/" + code + "/rematch", Map.of("accept", false));
        assertThat(next(topic, "REMATCH_CHANGED").at("/payload/accept").asBoolean()).isFalse();
        hostSession.send("/app/rooms/" + code + "/rematch", Map.of("accept", true));
        guestSession.send("/app/rooms/" + code + "/rematch", Map.of("accept", true));
        assertThat(poll(topic, "COUNTDOWN", 1000)).isNull();
        assertThat(roomStatus()).isEqualTo("FINISHED");
    }

    private String roomStatus() {
        return jdbc.queryForObject("SELECT status FROM game_rooms WHERE room_code = ?", String.class, code);
    }

    private Long seriesWinner(long id) {
        return jdbc.queryForObject("SELECT series_winner_user_id FROM matches WHERE id = ?", Long.class, id);
    }

    /** 그 사람 보드를 막아 topout을 보낸다(서버 확인 통과) */
    private void topout(StompSession session, long userId) {
        states.save(matchId, userId, blockedState());
        session.send("/app/rooms/" + code + "/topout", Map.of("seq", 0));
    }

    private static PlayerState blockedState() {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < Board.HEIGHT; i++) rows.add("GGGGGGGGG0");
        return new PlayerState(Board.fromBottomRows(rows), 0, 0, null, false, List.of(), -1, false, 0, 0, 1, 0, 0, 0, 0, null);
    }

    private JsonNode next(BlockingQueue<JsonNode> queue, String type) throws InterruptedException {
        return next(queue, type, 5000);
    }

    private JsonNode next(BlockingQueue<JsonNode> queue, String type, long millis) throws InterruptedException {
        JsonNode m = poll(queue, type, millis);
        if (m == null) throw new AssertionError(type + " 메시지를 받지 못했다");
        return m;
    }

    private JsonNode poll(BlockingQueue<JsonNode> queue, String type, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            JsonNode m = queue.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (m != null && type.equals(m.path("type").asText())) return m;
        }
        return null;
    }

    private StompSession connect(Player p) throws Exception {
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer " + p.token());
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connect,
                new StompSessionHandlerAdapter() { }).get(5, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    private BlockingQueue<JsonNode> subscribe(StompSession session, String destination) throws InterruptedException {
        BlockingQueue<JsonNode> queue = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add((JsonNode) payload);
            }
        });
        Thread.sleep(150);
        return queue;
    }

    private Player guest(String nickname) throws Exception {
        JsonNode data = json.readTree(mvc.perform(post("/api/v1/auth/guest").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"" + nickname + "\"}")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
        return new Player(data.get("userId").asLong(), data.get("accessToken").asText());
    }
}
