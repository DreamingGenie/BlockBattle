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
 * API 명세 5.2·5.5·5.6, BR-05(POL-005), GR-08을 실제 STOMP로 확인한다.
 * 깨지면: 조작된 lock이 받아들여지거나, 정상 lock이 거부되거나, 보드·공격 중계 계약이 어긋났다는 뜻이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(ContainersConfig.class)
class FriendLockTest {

    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PlayerStateRepository states;

    WebSocketStompClient client;
    final List<StompSession> sessions = new ArrayList<>();

    record Player(long userId, String token) {
    }

    long host;
    long guest;
    String code;
    long matchId;
    PieceSequence sequence;
    StompSession hostSession;
    BlockingQueue<JsonNode> topic;
    BlockingQueue<JsonNode> hostErrors;

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

        Player h = guest("오션");
        Player g = guest("민수");
        host = h.userId();
        guest = g.userId();
        code = json.readTree(mvc.perform(post("/api/v1/rooms").header("Authorization", "Bearer " + h.token())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getContentAsString())
                .at("/data/roomCode").asText();
        mvc.perform(post("/api/v1/rooms/" + code + "/join").header("Authorization", "Bearer " + g.token()));

        hostSession = connect(h);
        topic = subscribe(hostSession, "/topic/rooms/" + code);
        hostErrors = subscribe(hostSession, "/user/queue/errors");
        StompSession guestSession = connect(g);
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
    void 정상_lock은_서버가_재계산해_BOARD를_방송하고_같은_seq는_무시한다() throws Exception {
        Piece first = sequence.at(0);
        Placement drop = hardDrop(Board.empty(), first);
        lock(1, first, drop, false, "DROP", 0);

        JsonNode board = next(topic, "BOARD").get("payload");
        assertThat(board.get("userId").asLong()).isEqualTo(host);
        assertThat(board.get("seq").asInt()).isEqualTo(1);
        assertThat(board.get("board").asText()).isEqualTo(Board.empty().place(drop).toString());
        assertThat(board.get("score").asInt()).isZero();
        assertThat(board.get("pendingGarbage").asInt()).isZero();
        PlayerState saved = states.load(matchId, host);
        assertThat(saved.seq()).isEqualTo(1);
        assertThat(saved.pieceIndex()).isEqualTo(1);

        lock(1, first, drop, false, "DROP", 0); // 같은 seq는 무시
        assertThat(topic.poll(700, TimeUnit.MILLISECONDS)).isNull();
        assertThat(hostErrors.poll(100, TimeUnit.MILLISECONDS)).isNull();
    }

    /** POL-005 v2·GR-07 ④. 깨지면: 친구 대전 점수에 드롭 점수가 빠지거나, 위조한 드롭 칸으로 정상 플레이보다 높은 점수를 얻는다 */
    @Test
    void 드롭_칸_수는_상한까지만_점수에_더하고_잘못된_값은_0으로_보며_lock은_받아들인다() throws Exception {
        Board board = Board.empty();
        Piece first = sequence.at(0);
        Placement p1 = hardDrop(board, first);
        lock(1, first, p1, Map.of("softDropCells", 3, "hardDropCells", 10));
        int expected = 3 + 2 * 10;
        assertThat(next(topic, "BOARD").at("/payload/score").asInt()).isEqualTo(expected);

        board = board.place(p1);
        Piece second = sequence.at(1);
        Placement p2 = hardDrop(board, second);
        lock(2, second, p2, Map.of("softDropCells", 2, "hardDropCells", 99)); // 합이 y를 넘으면 하드 칸부터 잘린다
        expected += 2 + 2 * (p2.y() - 2);
        assertThat(next(topic, "BOARD").at("/payload/score").asInt()).isEqualTo(expected);

        board = board.place(p2);
        Piece third = sequence.at(2);
        lock(3, third, hardDrop(board, third), Map.of("softDropCells", "abc", "hardDropCells", -5));
        JsonNode accepted = next(topic, "BOARD").get("payload");
        assertThat(accepted.get("seq").asInt()).isEqualTo(3);
        assertThat(accepted.get("score").asInt()).isEqualTo(expected);
        assertThat(hostErrors.poll(300, TimeUnit.MILLISECONDS)).as("드롭 칸 값은 INVALID_LOCK 사유가 아니다").isNull();
        assertThat(states.load(matchId, host).score()).isEqualTo(expected);
    }

    @Test
    void seq를_건너뛰거나_나올_수_없는_블록이거나_위치가_틀리거나_줄_수가_다르면_INVALID_LOCK() throws Exception {
        Piece first = sequence.at(0);
        Placement drop = hardDrop(Board.empty(), first);

        lock(2, first, drop, false, "DROP", 0); // seq 건너뜀
        assertInvalid(2, 1, Board.empty());

        Piece wrong = first == Piece.O ? Piece.I : Piece.O;
        lock(1, wrong, hardDrop(Board.empty(), wrong), false, "DROP", 0); // 시드 순서와 다른 블록
        assertInvalid(1, 1, Board.empty());

        lock(1, first, new Placement(first, 0, 3, 5), false, "MOVE", 0); // 떠 있는 위치
        assertInvalid(1, 1, Board.empty());

        lock(1, first, drop, false, "DROP", 1); // 지운 줄 수 조작
        assertInvalid(1, 1, Board.empty());

        assertThat(topic.poll(500, TimeUnit.MILLISECONDS)).as("불일치 lock은 방송하지 않는다").isNull();
        assertThat(states.load(matchId, host).seq()).as("서버 상태는 그대로").isZero();
    }

    @Test
    void 빈_홀드를_쓰면_다음_블록을_놓고_홀드에는_첫_블록이_남는다() throws Exception {
        Piece held = sequence.at(0);
        Piece placed = sequence.at(1);
        Placement drop = hardDrop(Board.empty(), placed);
        lock(1, placed, drop, true, "DROP", 0);
        next(topic, "BOARD");
        PlayerState saved = states.load(matchId, host);
        assertThat(saved.hold()).isEqualTo(held);
        assertThat(saved.pieceIndex()).isEqualTo(2);
    }

    @Test
    void 테트리스면_점수와_공격을_계산하고_상대_대기열에_넣어_ATTACK을_보낸다() throws Exception {
        int iIndex = 0;
        while (sequence.at(iIndex) != Piece.I) iIndex++;
        // 맨 위에 한 칸을 남겨 퍼펙트 클리어(+10)가 되지 않게 한다
        Board well = Board.fromBottomRows(List.of("G000000000", "GGGGGGGGG0", "GGGGGGGGG0", "GGGGGGGGG0", "GGGGGGGGG0"));
        PlayerState start = PlayerState.initial();
        states.save(matchId, host, new PlayerState(well, 0, iIndex, null, false, List.of(), -1, false, 0, 0, 1, 0, 0, 0, 0, null));

        lock(1, Piece.I, new Placement(Piece.I, 1, 7, 18), false, "DROP", 4);
        assertThat(hostErrors.poll(300, TimeUnit.MILLISECONDS)).isNull();
        JsonNode board = next(topic, "BOARD").get("payload");
        assertThat(board.get("score").asInt()).isEqualTo(800);
        assertThat(board.get("linesCleared").asInt()).isEqualTo(4);
        assertThat(board.get("b2b").asBoolean()).isTrue();
        JsonNode attack = next(topic, "ATTACK").get("payload");
        assertThat(attack.get("fromUserId").asLong()).isEqualTo(host);
        assertThat(attack.get("toUserId").asLong()).isEqualTo(guest);
        assertThat(attack.get("lines").asInt()).isEqualTo(4);
        assertThat(attack.get("holeColumn").asInt()).isBetween(0, 9);

        PlayerState opp = states.load(matchId, guest);
        assertThat(opp.pendingLines()).isEqualTo(4);
        assertThat(opp.pending().get(0).holeColumn()).isEqualTo(attack.get("holeColumn").asInt());
        assertThat(opp.attackReceived()).isEqualTo(4);
        assertThat(states.load(matchId, host).attackSent()).isEqualTo(4);
        assertThat(start.combo()).isEqualTo(-1);
    }

    private void assertInvalid(int seq, int nextSeq, Board serverBoard) throws InterruptedException {
        JsonNode error = hostErrors.poll(5, TimeUnit.SECONDS);
        assertThat(error).isNotNull();
        assertThat(error.get("code").asText()).isEqualTo("INVALID_LOCK");
        assertThat(error.at("/payload/seq").asInt()).isEqualTo(seq);
        assertThat(error.at("/payload/nextSeq").asInt()).isEqualTo(nextSeq);
        assertThat(error.at("/payload/board").asText()).isEqualTo(serverBoard.toString());
    }

    private static Placement hardDrop(Board board, Piece piece) {
        Placement p = new Placement(piece, 0, 3, 0);
        while (board.fits(new Placement(piece, 0, 3, p.y() + 1))) p = new Placement(piece, 0, 3, p.y() + 1);
        return p;
    }

    private void lock(int seq, Piece piece, Placement p, boolean holdUsed, String lastMove, int lines) {
        Map<String, Object> body = new HashMap<>();
        body.put("seq", seq);
        body.put("piece", piece.name());
        body.put("rotation", p.rotation());
        body.put("x", p.x());
        body.put("y", p.y());
        body.put("holdUsed", holdUsed);
        body.put("lastMove", lastMove);
        body.put("linesCleared", lines);
        hostSession.send("/app/rooms/" + code + "/lock", body);
    }

    /** 줄을 지우지 않는 하드 드롭 lock에 드롭 칸 필드를 붙여 보낸다 */
    private void lock(int seq, Piece piece, Placement p, Map<String, Object> dropCells) {
        Map<String, Object> body = new HashMap<>(dropCells);
        body.put("seq", seq);
        body.put("piece", piece.name());
        body.put("rotation", p.rotation());
        body.put("x", p.x());
        body.put("y", p.y());
        body.put("holdUsed", false);
        body.put("lastMove", "DROP");
        body.put("linesCleared", 0);
        hostSession.send("/app/rooms/" + code + "/lock", body);
    }

    private JsonNode next(BlockingQueue<JsonNode> queue, String type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode m = queue.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (m != null && type.equals(m.path("type").asText())) return m;
        }
        throw new AssertionError(type + " 메시지를 받지 못했다");
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
