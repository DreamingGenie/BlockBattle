package com.blockbattle.battle;

import static org.assertj.core.api.Assertions.assertThat;
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
 * BR-04(승패)·BR-06(끊김·재접속)·BR-09(친구 전적), API 명세 5.3·5.5·5.7을 실제 STOMP로 확인한다.
 * 깨지면: 승패 사유·승자가 틀리게 저장되거나, 끊김 유예·RESYNC 계약이 어긋났다는 뜻이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(ContainersConfig.class)
class FriendOutcomeTest {

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
    PieceSequence sequence;
    StompSession hostSession;
    StompSession guestSession;
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

        h = guest("오션");
        g = guest("민수");
        host = h.userId();
        guest = g.userId();
        code = json.readTree(mvc.perform(post("/api/v1/rooms").header("Authorization", "Bearer " + h.token())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getContentAsString())
                .at("/data/roomCode").asText();
        mvc.perform(post("/api/v1/rooms/" + code + "/join").header("Authorization", "Bearer " + g.token()));

        hostSession = connect(h);
        topic = subscribe(hostSession, "/topic/rooms/" + code);
        guestSession = connect(g);
        hostSession.send("/app/rooms/" + code + "/ready", Map.of("ready", true));
        guestSession.send("/app/rooms/" + code + "/ready", Map.of("ready", true));
        JsonNode countdown = next(topic, "COUNTDOWN").get("payload");
        matchId = countdown.get("matchId").asLong();
        sequence = new PieceSequence(countdown.get("seed").asLong());
    }

    @AfterEach
    void tearDown() {
        sessions.forEach(s -> { if (s.isConnected()) s.disconnect(); });
        client.stop();
    }

    @Test
    void 서버_보드로_확인된_topout은_먼저_낸_쪽_패배로_저장하고_GAME_OVER를_보낸다() throws Exception {
        states.save(matchId, host, blockedState());
        hostSession.send("/app/rooms/" + code + "/topout", Map.of("seq", 0));

        JsonNode over = next(topic, "GAME_OVER").get("payload");
        assertThat(over.get("matchId").asLong()).isEqualTo(matchId);
        assertThat(over.get("reason").asText()).isEqualTo("TOP_OUT");
        assertThat(over.get("winnerUserId").asLong()).isEqualTo(guest);
        assertThat(over.get("seriesWinnerUserId").asLong()).as("1판 방은 라운드 승자가 시리즈 승자").isEqualTo(guest);
        assertThat(over.get("nextRoundStartsAt").isNull()).isTrue();
        assertThat(over.at("/seriesWins/" + guest).asInt()).isEqualTo(1);
        assertThat(over.at("/seriesWins/" + host).asInt()).isZero();
        assertThat(over.get("stats")).hasSize(2);

        Map<String, Object> m = jdbc.queryForMap(
                "SELECT status, end_reason, winner_user_id, series_winner_user_id, ended_at, duration_ms FROM matches WHERE id = ?", matchId);
        assertThat(m.get("status")).isEqualTo("FINISHED");
        assertThat(m.get("end_reason")).isEqualTo("TOP_OUT");
        assertThat(((Number) m.get("winner_user_id")).longValue()).isEqualTo(guest);
        assertThat(((Number) m.get("series_winner_user_id")).longValue()).isEqualTo(guest);
        assertThat(m.get("ended_at")).isNotNull();
        assertThat(result(host)).isEqualTo("LOSE");
        assertThat(result(guest)).isEqualTo("WIN");
        assertThat(friend(guest, "friend_wins")).isEqualTo(1);
        assertThat(friend(host, "friend_losses")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM game_rooms WHERE room_code = ?", String.class, code))
                .isEqualTo("FINISHED");
    }

    @Test
    void 블록이_나올_수_있거나_seq가_마지막_lock과_다르면_topout을_무시한다() throws Exception {
        hostSession.send("/app/rooms/" + code + "/topout", Map.of("seq", 0)); // 빈 보드
        states.save(matchId, guest, blockedState());
        guestSession.send("/app/rooms/" + code + "/topout", Map.of("seq", 3)); // 마지막 lock seq는 0
        assertThat(poll(topic, "GAME_OVER", 800)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM matches WHERE id = ?", String.class, matchId)).isEqualTo("PLAYING");
    }

    /**
     * 깨지면: 클라이언트의 topout 신고가 마지막 lock보다 먼저 처리되거나 사라졌을 때 경기가 끝나지 않고,
     * 먼저 천장에 닿은 사람 대신 늦게 topout한 상대가 진다(11-19 제보된 버그).
     */
    @Test
    void 마지막_lock으로_다음_블록이_막히면_topout_신고_없이도_서버가_판정한다() throws Exception {
        int oIndex = 0;
        while (sequence.at(oIndex) != Piece.O) oIndex++;
        List<String> rows = new ArrayList<>();
        for (int i = 2; i < Board.HEIGHT; i++) rows.add("GGGGGGGGG0"); // 맨 위 2행(생성 자리)만 비었다, 줄은 지워지지 않는다
        states.save(matchId, host, new PlayerState(Board.fromBottomRows(rows), 0, oIndex, null, false, List.of(), -1, false,
                0, 0, 1, 0, 0, 0, 0, null));
        Map<String, Object> body = new HashMap<>();
        body.put("seq", 1);
        body.put("piece", "O");
        body.put("rotation", 0);
        body.put("x", 3);
        body.put("y", 0); // 생성 자리 그대로 놓는다 → 다음 블록이 나올 곳이 없다
        body.put("holdUsed", false);
        body.put("lastMove", "DROP");
        body.put("linesCleared", 0);
        hostSession.send("/app/rooms/" + code + "/lock", body);

        JsonNode over = next(topic, "GAME_OVER").get("payload");
        assertThat(over.get("reason").asText()).isEqualTo("TOP_OUT");
        assertThat(over.get("winnerUserId").asLong()).isEqualTo(guest);
    }

    /** 깨지면: 서버 보드와 어긋난 topout 신고를 그냥 버려, 신고한 쪽 화면은 멈추고 경기는 끝나지 않는다 */
    @Test
    void 서버_보드에서는_블록이_나올_수_있는데_topout을_신고하면_INVALID_LOCK과_서버_보드를_보낸다() throws Exception {
        BlockingQueue<JsonNode> errors = subscribe(hostSession, "/user/queue/errors");
        hostSession.send("/app/rooms/" + code + "/topout", Map.of("seq", 0)); // 빈 보드

        JsonNode error = errors.poll(5, TimeUnit.SECONDS);
        assertThat(error).isNotNull();
        assertThat(error.get("code").asText()).isEqualTo("INVALID_LOCK");
        assertThat(error.at("/payload/board").asText()).isEqualTo(Board.empty().toString());
        assertThat(error.at("/payload/nextSeq").asInt()).isEqualTo(1);
        assertThat(poll(topic, "GAME_OVER", 500)).isNull();
    }

    /** 깨지면: 홀드로 꺼낸 블록이 막혀 끝난 경우를 서버가 인정하지 않아 그 클라이언트가 계속 되돌려진다 */
    @Test
    void 홀드로_꺼낸_블록이_막히면_holdUsed로_신고한_topout을_받는다() throws Exception {
        int index = 0;
        while (sequence.at(index) == Piece.I || sequence.at(index + 1) != Piece.I) index++;
        List<String> rows = new ArrayList<>();
        rows.add("000000G000"); // 위에서 둘째 행 6열: I(1행 3~6열)만 막고 나머지 블록은 나올 수 있다
        for (int i = 2; i < Board.HEIGHT; i++) rows.add("0000000000");
        states.save(matchId, host, new PlayerState(Board.fromBottomRows(rows), 0, index, null, false, List.of(), -1, false,
                0, 0, 1, 0, 0, 0, 0, null));
        hostSession.send("/app/rooms/" + code + "/topout", Map.of("seq", 0, "holdUsed", true));

        JsonNode over = next(topic, "GAME_OVER").get("payload");
        assertThat(over.get("winnerUserId").asLong()).isEqualTo(guest);
    }

    @Test
    void 두_사람이_100ms_안에_topout하면_무승부이고_전적은_바뀌지_않는다() throws Exception {
        states.save(matchId, host, blockedState());
        states.save(matchId, guest, blockedState());
        hostSession.send("/app/rooms/" + code + "/topout", Map.of("seq", 0));
        guestSession.send("/app/rooms/" + code + "/topout", Map.of("seq", 0));

        JsonNode over = next(topic, "GAME_OVER").get("payload");
        assertThat(over.get("reason").asText()).isEqualTo("DRAW");
        assertThat(over.get("winnerUserId").isNull()).isTrue();
        assertThat(poll(topic, "GAME_OVER", 500)).as("한 번만 확정된다").isNull();
        assertThat(jdbc.queryForObject("SELECT end_reason FROM matches WHERE id = ?", String.class, matchId)).isEqualTo("DRAW");
        assertThat(result(host)).isEqualTo("NONE");
        assertThat(result(guest)).isEqualTo("NONE");
        assertThat(friend(host, "friend_wins") + friend(host, "friend_losses")
                + friend(guest, "friend_wins") + friend(guest, "friend_losses")).isZero();
    }

    /** T-41: 깨지면 봇을 쓴 쪽의 친구 대전 승리가 전적에 들어가거나, 화면이 양쪽에 알릴 근거(botSuspectedUserIds)가 사라진다 */
    @Test
    void 사람이_낼_수_없는_속도로_놓은_쪽이_있으면_봇_의심으로_양쪽_전적에_넣지_않는다() throws Exception {
        // 상대(guest)가 시작 직후 300블록을 놓은 상태 — 서버가 잰 라운드 시간 기준 초당 7블록을 크게 넘는다
        states.save(matchId, guest, new PlayerState(Board.empty(), 300, 300, null, false, List.of(), -1, false, 90_000, 120, 13,
                300, 40, 0, 5, null));
        states.save(matchId, host, blockedState());
        hostSession.send("/app/rooms/" + code + "/topout", Map.of("seq", 0));

        JsonNode over = next(topic, "GAME_OVER").get("payload");
        assertThat(over.get("winnerUserId").asLong()).isEqualTo(guest);
        assertThat(over.get("botSuspectedUserIds")).hasSize(1);
        assertThat(over.get("botSuspectedUserIds").get(0).asLong()).isEqualTo(guest);
        assertThat(jdbc.queryForObject("SELECT ranked FROM matches WHERE id = ?", Boolean.class, matchId)).isFalse();
        assertThat(friend(host, "friend_wins") + friend(host, "friend_losses")
                + friend(guest, "friend_wins") + friend(guest, "friend_losses")).as("소명 전까지 통계에 넣지 않는다").isZero();
    }

    @Test
    void 경기_중_방을_나가면_나간_쪽_LEFT_패배다() throws Exception {
        mvc.perform(post("/api/v1/rooms/" + code + "/leave").header("Authorization", "Bearer " + g.token()));

        JsonNode over = next(topic, "GAME_OVER").get("payload");
        assertThat(over.get("reason").asText()).isEqualTo("LEFT");
        assertThat(over.get("winnerUserId").asLong()).isEqualTo(host);
        assertThat(jdbc.queryForObject("SELECT end_reason FROM matches WHERE id = ?", String.class, matchId)).isEqualTo("LEFT");
        assertThat(friend(guest, "friend_losses")).isEqualTo(1);
    }

    @Test
    void 끊긴_동안_보드는_멈추고_받은_공격은_쌓이며_10초_안에_다시_붙으면_RESYNC로_이어_간다() throws Exception {
        long disconnectedAt = System.currentTimeMillis();
        guestSession.disconnect();
        JsonNode left = next(topic, "PLAYER_LEFT").get("payload");
        assertThat(left.get("userId").asLong()).isEqualTo(guest);
        assertThat(left.get("reason").asText()).isEqualTo("DISCONNECTED");
        Board guestBoard = states.load(matchId, guest).board();

        tetris(); // 상대 경기는 계속되고 공격은 끊긴 쪽 대기열에 쌓인다
        JsonNode attack = next(topic, "ATTACK").get("payload");
        assertThat(states.load(matchId, guest).pendingLines()).isEqualTo(4);
        assertThat(states.load(matchId, guest).board().toString()).as("끊긴 쪽 보드는 그대로").isEqualTo(guestBoard.toString());

        StompSession again = connect(g);
        BlockingQueue<JsonNode> state = subscribe(again, "/user/queue/state");
        assertThat(next(topic, "PLAYER_RECONNECTED").at("/payload/userId").asLong()).isEqualTo(guest);
        JsonNode resync = state.poll(5, TimeUnit.SECONDS);
        assertThat(resync).isNotNull();
        assertThat(resync.get("type").asText()).isEqualTo("RESYNC");
        assertThat(resync.get("roomCode").asText()).isEqualTo(code);
        JsonNode p = resync.get("payload");
        assertThat(p.get("matchId").asLong()).isEqualTo(matchId);
        assertThat(p.get("seq").asInt()).isZero();
        assertThat(p.get("board").asText()).isEqualTo(guestBoard.toString());
        assertThat(p.at("/pendingGarbage/0/lines").asInt()).isEqualTo(4);
        assertThat(p.at("/pendingGarbage/0/holeColumn").asInt()).isEqualTo(attack.get("holeColumn").asInt());
        assertThat(p.at("/opponent/userId").asLong()).isEqualTo(host);
        assertThat(p.at("/opponent/board").asText()).isEqualTo(states.load(matchId, host).board().toString());
        // POL-006 v2: 새로 연 화면이 블록 순서·성적을 되살릴 시드와 내 성적
        PlayerState mine = states.load(matchId, guest);
        assertThat(p.get("seed").asLong()).isEqualTo(jdbc.queryForObject("SELECT seed FROM matches WHERE id = ?", Long.class, matchId));
        assertThat(p.at("/me/score").asInt()).isEqualTo(mine.score());
        assertThat(p.at("/me/linesCleared").asInt()).isEqualTo(mine.linesCleared());
        assertThat(p.at("/me/level").asInt()).isEqualTo(mine.level());
        assertThat(p.at("/me/combo").asInt()).isEqualTo(mine.combo());
        assertThat(p.at("/me/b2b").asBoolean()).isEqualTo(mine.b2b());

        long waitUntilGraceOver = disconnectedAt + 10_800 - System.currentTimeMillis();
        assertThat(poll(topic, "GAME_OVER", Math.max(waitUntilGraceOver, 100))).as("재접속하면 타이머가 취소된다").isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM matches WHERE id = ?", String.class, matchId)).isEqualTo("PLAYING");
    }

    @Test
    void 유예_10초_안에_다시_붙지_않으면_끊긴_쪽_DISCONNECT_패배다() throws Exception {
        guestSession.disconnect();
        next(topic, "PLAYER_LEFT");
        JsonNode over = poll(topic, "GAME_OVER", 12_000);
        assertThat(over).isNotNull();
        assertThat(over.at("/payload/reason").asText()).isEqualTo("DISCONNECT");
        assertThat(over.at("/payload/winnerUserId").asLong()).isEqualTo(host);
        assertThat(jdbc.queryForObject("SELECT end_reason FROM matches WHERE id = ?", String.class, matchId)).isEqualTo("DISCONNECT");
        assertThat(friend(host, "friend_wins")).isEqualTo(1);
        assertThat(friend(guest, "friend_losses")).isEqualTo(1);
    }

    @Test
    void 두_사람이_모두_끊겨_10초가_지나면_ABORTED이고_전적은_바뀌지_않는다() throws Exception {
        guestSession.disconnect();
        next(topic, "PLAYER_LEFT");
        hostSession.disconnect();
        long deadline = System.currentTimeMillis() + 13_000;
        String status = "PLAYING";
        while (System.currentTimeMillis() < deadline && "PLAYING".equals(status)) {
            Thread.sleep(250);
            status = jdbc.queryForObject("SELECT status FROM matches WHERE id = ?", String.class, matchId);
        }
        assertThat(status).isEqualTo("ABORTED");
        assertThat(jdbc.queryForObject("SELECT end_reason FROM matches WHERE id = ?", String.class, matchId)).isEqualTo("DISCONNECT");
        assertThat(result(host)).isEqualTo("NONE");
        assertThat(friend(host, "friend_losses") + friend(guest, "friend_losses")).isZero();
    }

    /** 모든 행의 3~6열이 막혀 어떤 블록도 생성 자리에 들어가지 않는 보드(줄은 지워지지 않는다) */
    private static PlayerState blockedState() {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < Board.HEIGHT; i++) rows.add("GGGGGGGGG0");
        return new PlayerState(Board.fromBottomRows(rows), 0, 0, null, false, List.of(), -1, false, 0, 0, 1, 0, 0, 0, 0, null);
    }

    /** 방장이 테트리스(4줄)로 상대에게 공격 4줄을 보낸다 */
    private void tetris() {
        int iIndex = 0;
        while (sequence.at(iIndex) != Piece.I) iIndex++;
        Board well = Board.fromBottomRows(List.of("G000000000", "GGGGGGGGG0", "GGGGGGGGG0", "GGGGGGGGG0", "GGGGGGGGG0"));
        states.save(matchId, host, new PlayerState(well, 0, iIndex, null, false, List.of(), -1, false, 0, 0, 1, 0, 0, 0, 0, null));
        Placement p = new Placement(Piece.I, 1, 7, 18);
        Map<String, Object> body = new HashMap<>();
        body.put("seq", 1);
        body.put("piece", "I");
        body.put("rotation", p.rotation());
        body.put("x", p.x());
        body.put("y", p.y());
        body.put("holdUsed", false);
        body.put("lastMove", "DROP");
        body.put("linesCleared", 4);
        hostSession.send("/app/rooms/" + code + "/lock", body);
    }

    private String result(long userId) {
        return jdbc.queryForObject("SELECT result FROM match_players WHERE match_id = ? AND user_id = ?", String.class, matchId, userId);
    }

    private int friend(long userId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM user_stats WHERE user_id = ?", Integer.class, userId);
    }

    private JsonNode next(BlockingQueue<JsonNode> queue, String type) throws InterruptedException {
        JsonNode m = poll(queue, type, 5000);
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
