package com.blockbattle.battle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.blockbattle.engine.Board;
import com.blockbattle.engine.Piece;
import com.blockbattle.engine.PieceSequence;
import com.blockbattle.engine.Placement;
import com.blockbattle.support.ContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
 * 최대 4인 친구 대전(2026-11-19). 3명 방으로 확인한다.
 * 깨지면: 다인 경기에서 탈락 순서대로 등수가 매겨지지 않거나, 한 명이 남기 전에 경기가 끝나거나, 1위만 승·나머지 패 전적,
 * 3인 이상 1판만, 방장 넘겨받기, 탈락한 사람에게 공격이 가지 않는 규칙이 어긋났다는 뜻이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(ContainersConfig.class)
class FriendMultiTest {

    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PlayerStateRepository states;
    @Autowired MatchOutcomeService outcomes;

    WebSocketStompClient client;
    final List<StompSession> sessions = Collections.synchronizedList(new ArrayList<>());

    record Player(long userId, String token) {
    }

    Player a;
    Player b;
    Player c;
    String code;
    StompSession sa;
    StompSession sb;
    StompSession sc;
    BlockingQueue<JsonNode> topic;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM match_players");
        jdbc.update("DELETE FROM matches");
        jdbc.update("DELETE FROM game_rooms");
        jdbc.update("DELETE FROM user_stats");
        jdbc.update("DELETE FROM users");
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());

        a = guest("방장");
        b = guest("둘째");
        c = guest("셋째");
        // 3판 2선승으로 만들어도 3명이면 1판만 한다
        code = json.readTree(mvc.perform(post("/api/v1/rooms").header("Authorization", "Bearer " + a.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"roundCount\":3}")).andReturn().getResponse()
                .getContentAsString()).at("/data/roomCode").asText();
        join(b);
        join(c);
        sa = connect(a);
        topic = subscribe(sa, "/topic/rooms/" + code);
        sb = connect(b);
        sc = connect(c);
    }

    @AfterEach
    void tearDown() {
        sessions.forEach(s -> { if (s.isConnected()) s.disconnect(); });
        client.stop();
    }

    @Test
    void 세_명이_모두_준비하면_시작하고_탈락_순서대로_등수를_매겨_마지막_한_명이_이긴다() throws Exception {
        long matchId = start();
        assertThat(jdbc.queryForList("SELECT slot, user_id FROM match_players WHERE match_id = ? ORDER BY slot", matchId))
                .as("방장 1번, 나머지는 userId 순").extracting(r -> ((Number) r.get("user_id")).longValue())
                .containsExactly(a.userId(), Math.min(b.userId(), c.userId()), Math.max(b.userId(), c.userId()));

        topout(sc, c, matchId);
        JsonNode out = next("PLAYER_OUT").get("payload");
        assertThat(out.get("userId").asLong()).isEqualTo(c.userId());
        assertThat(out.get("placement").asInt()).isEqualTo(3);
        assertThat(poll("GAME_OVER", 500)).as("두 명이 남았으니 계속한다").isNull();

        topout(sb, b, matchId);
        JsonNode over = next("GAME_OVER").get("payload");
        assertThat(over.get("winnerUserId").asLong()).isEqualTo(a.userId());
        assertThat(over.get("reason").asText()).isEqualTo("TOP_OUT");
        assertThat(over.at("/placements/" + a.userId()).asInt()).isEqualTo(1);
        assertThat(over.at("/placements/" + b.userId()).asInt()).isEqualTo(2);
        assertThat(over.at("/placements/" + c.userId()).asInt()).isEqualTo(3);
        assertThat(over.get("seriesWinnerUserId").asLong()).as("3명이면 1판으로 끝난다").isEqualTo(a.userId());
        assertThat(over.get("nextRoundStartsAt").isNull()).isTrue();
        assertThat(over.get("stats")).hasSize(3);

        assertThat(placement(matchId, a)).isEqualTo(1);
        assertThat(placement(matchId, c)).isEqualTo(3);
        assertThat(result(matchId, a)).isEqualTo("WIN");
        assertThat(result(matchId, b)).isEqualTo("LOSE");
        assertThat(result(matchId, c)).isEqualTo("LOSE");
        assertThat(friend(a, "friend_wins")).isEqualTo(1);
        assertThat(friend(b, "friend_losses")).isEqualTo(1);
        assertThat(friend(c, "friend_losses")).as("1위만 승, 나머지는 패").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM game_rooms WHERE room_code = ?", String.class, code)).isEqualTo("FINISHED");

        JsonNode list = json.readTree(mvc.perform(get("/api/v1/users/me/matches?mode=FRIEND").header("Authorization", "Bearer " + a.token()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).at("/data/items");
        assertThat(list).as("상대가 여럿이어도 경기는 한 줄").hasSize(1);
        assertThat(list.get(0).get("playerCount").asInt()).isEqualTo(3);
        assertThat(list.get(0).get("myPlacement").asInt()).isEqualTo(1);
    }

    /** 깨지면: 정원 4명 방에서 4번 슬롯이 만들어지지 않거나, 4위부터 차례로 등수가 매겨지지 않는다 */
    @Test
    void 네_명_방은_4위부터_차례로_탈락하고_마지막_한_명이_이긴다() throws Exception {
        Player d = guest("넷째");
        join(d);
        StompSession sd = connect(d);
        sd.send("/app/rooms/" + code + "/ready", Map.of("ready", true));
        long matchId = start();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM match_players WHERE match_id = ?", Integer.class, matchId))
                .isEqualTo(4);

        topout(sd, d, matchId);
        assertThat(next("PLAYER_OUT").at("/payload/placement").asInt()).isEqualTo(4);
        topout(sc, c, matchId);
        assertThat(next("PLAYER_OUT").at("/payload/placement").asInt()).isEqualTo(3);
        assertThat(poll("GAME_OVER", 300)).isNull();
        topout(sb, b, matchId);

        JsonNode over = next("GAME_OVER").get("payload");
        assertThat(over.get("winnerUserId").asLong()).isEqualTo(a.userId());
        assertThat(over.at("/placements/" + d.userId()).asInt()).isEqualTo(4);
        assertThat(over.at("/placements/" + b.userId()).asInt()).isEqualTo(2);
        assertThat(over.get("stats")).hasSize(4);
        assertThat(placement(matchId, d)).isEqualTo(4);
        assertThat(friend(d, "friend_losses")).isEqualTo(1);
        assertThat(friend(a, "friend_wins")).isEqualTo(1);
    }

    @Test
    void 마지막_두_사람이_함께_topout하면_무승부이고_먼저_탈락한_사람만_LOSE다() throws Exception {
        long matchId = start();
        topout(sc, c, matchId);
        next("PLAYER_OUT");
        states.save(matchId, a.userId(), blockedState());
        states.save(matchId, b.userId(), blockedState());
        sa.send("/app/rooms/" + code + "/topout", Map.of("seq", 0));
        sb.send("/app/rooms/" + code + "/topout", Map.of("seq", 0));

        JsonNode over = next("GAME_OVER").get("payload");
        assertThat(over.get("reason").asText()).isEqualTo("DRAW");
        assertThat(over.get("winnerUserId").isNull()).isTrue();
        assertThat(result(matchId, a)).isEqualTo("NONE");
        assertThat(result(matchId, b)).isEqualTo("NONE");
        assertThat(result(matchId, c)).isEqualTo("LOSE");
        assertThat(friend(c, "friend_losses")).as("무승부 경기는 전적을 바꾸지 않는다").isZero();
    }

    @Test
    void 공격은_탈락한_사람에게_가지_않는다() throws Exception {
        long matchId = start();
        topout(sc, c, matchId);
        next("PLAYER_OUT");
        for (int i = 0; i < 3; i++) {
            tetris(sa, a, matchId, i + 1);
            JsonNode attack = next("ATTACK").get("payload");
            assertThat(attack.get("toUserId").asLong()).isEqualTo(b.userId());
        }
    }

    @Test
    void 방장이_나가도_두_사람_이상_남으면_userId가_가장_작은_사람이_방장을_넘겨받는다() throws Exception {
        topic = subscribe(sb, "/topic/rooms/" + code); // 나간 방장의 구독은 지워지므로 남은 사람 쪽에서 받는다
        mvc.perform(post("/api/v1/rooms/" + code + "/leave").header("Authorization", "Bearer " + a.token()));
        JsonNode changed = next("HOST_CHANGED");
        assertThat(changed.at("/payload/userId").asLong()).isEqualTo(Math.min(b.userId(), c.userId()));
        JsonNode room = json.readTree(mvc.perform(get("/api/v1/rooms/" + code).header("Authorization", "Bearer " + b.token()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
        assertThat(room.get("status").asText()).isEqualTo("WAITING");
        assertThat(room.get("hostUserId").asLong()).isEqualTo(Math.min(b.userId(), c.userId()));
        assertThat(room.get("players")).hasSize(2);
    }

    private long start() throws InterruptedException {
        sb.send("/app/rooms/" + code + "/ready", Map.of("ready", true));
        sc.send("/app/rooms/" + code + "/ready", Map.of("ready", true));
        Thread.sleep(200);
        sa.send("/app/rooms/" + code + "/ready", Map.of("ready", true)); // 방장의 준비 = 시작
        return next("COUNTDOWN").at("/payload/matchId").asLong();
    }

    private void topout(StompSession session, Player p, long matchId) {
        states.save(matchId, p.userId(), blockedState());
        session.send("/app/rooms/" + code + "/topout", Map.of("seq", 0));
    }

    /** 깨지면: 끊겨 탈락한 사람이 다시 붙어 보낸 lock이 받아들여져, 탈락한 뒤에도 남은 사람을 공격할 수 있다 */
    @Test
    void 탈락한_사람이_보낸_lock은_무시한다() throws Exception {
        long matchId = start();
        outcomes.eliminate(code, matchId, List.of(c.userId()), MatchOutcomeService.Reason.DISCONNECT);
        assertThat(next("PLAYER_OUT").at("/payload/reason").asText()).isEqualTo("DISCONNECT");
        tetris(sc, c, matchId, 1);
        assertThat(poll("BOARD", 800)).isNull();
        assertThat(poll("ATTACK", 200)).isNull();
    }

    /** p가 테트리스(4줄)로 공격 4줄을 보낸다. seq는 1부터 */
    private void tetris(StompSession session, Player player, long matchId, int seq) {
        PieceSequence sequence = new PieceSequence(jdbc.queryForObject("SELECT seed FROM matches WHERE id = ?", Long.class, matchId));
        int iIndex = 0;
        while (sequence.at(iIndex) != Piece.I) iIndex++;
        Board well = Board.fromBottomRows(List.of("G000000000", "GGGGGGGGG0", "GGGGGGGGG0", "GGGGGGGGG0", "GGGGGGGGG0"));
        PlayerState before = states.load(matchId, player.userId());
        states.save(matchId, player.userId(), new PlayerState(well, seq - 1, iIndex, null, false, List.of(), -1, false, 0, 0, 1, 0,
                0, 0, 0, before.topoutAt()));
        Placement p = new Placement(Piece.I, 1, 7, 18);
        Map<String, Object> body = new HashMap<>();
        body.put("seq", seq);
        body.put("piece", "I");
        body.put("rotation", p.rotation());
        body.put("x", p.x());
        body.put("y", p.y());
        body.put("holdUsed", false);
        body.put("lastMove", "DROP");
        body.put("linesCleared", 4);
        session.send("/app/rooms/" + code + "/lock", body);
    }

    /** 모든 행의 3~6열이 막혀 어떤 블록도 생성 자리에 들어가지 않는 보드(줄은 지워지지 않는다) */
    private static PlayerState blockedState() {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < Board.HEIGHT; i++) rows.add("GGGGGGGGG0");
        return new PlayerState(Board.fromBottomRows(rows), 0, 0, null, false, List.of(), -1, false, 0, 0, 1, 0, 0, 0, 0, null);
    }

    private Integer placement(long matchId, Player p) {
        return jdbc.queryForObject("SELECT placement FROM match_players WHERE match_id = ? AND user_id = ?", Integer.class,
                matchId, p.userId());
    }

    private String result(long matchId, Player p) {
        return jdbc.queryForObject("SELECT result FROM match_players WHERE match_id = ? AND user_id = ?", String.class,
                matchId, p.userId());
    }

    private int friend(Player p, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM user_stats WHERE user_id = ?", Integer.class, p.userId());
    }

    private void join(Player p) throws Exception {
        mvc.perform(post("/api/v1/rooms/" + code + "/join").header("Authorization", "Bearer " + p.token()));
    }

    private JsonNode next(String type) throws InterruptedException {
        JsonNode m = poll(type, 5000);
        if (m == null) throw new AssertionError(type + " 메시지를 받지 못했다");
        return m;
    }

    private JsonNode poll(String type, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            JsonNode m = topic.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
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
