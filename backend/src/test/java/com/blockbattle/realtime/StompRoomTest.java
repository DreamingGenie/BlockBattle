package com.blockbattle.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.blockbattle.store.redis.RedisKeys;
import com.blockbattle.support.ContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 * API 명세 5장·POL-013·REQ-011·REQ-018을 실제 STOMP 클라이언트로 확인한다.
 * 깨지면: 인증 없는 연결이 열리거나, 방 밖 사용자가 방 메시지를 받거나 보내거나, 준비·카운트다운 계약(startsAt = 전송 + 3초)이 어긋났다는 뜻이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(ContainersConfig.class)
class StompRoomTest {

    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Value("${app.jwt.secret}") String secret;

    WebSocketStompClient client;
    final List<StompSession> sessions = new ArrayList<>();

    record Player(long userId, String token) {
    }

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

    @Test
    void 토큰이_없거나_위조면_UNAUTHORIZED_만료면_TOKEN_EXPIRED_ERROR_프레임으로_거부한다() throws Exception {
        assertConnectRejected(null, "UNAUTHORIZED");
        assertConnectRejected("Bearer not-a-jwt", "UNAUTHORIZED");
        String expired = Jwts.builder().subject("1").issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .expiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
        assertConnectRejected("Bearer " + expired, "TOKEN_EXPIRED");
    }

    @Test
    void 방_밖_사용자의_구독과_발행은_무시하고_개인_오류로_NOT_PARTICIPANT를_보낸다() throws Exception {
        Player host = guest("오션");
        String code = createRoom(host);
        Player outsider = guest("손님");

        StompSession hostSession = connect(host);
        BlockingQueue<JsonNode> hostTopic = subscribe(hostSession, "/topic/rooms/" + code);
        StompSession outsiderSession = connect(outsider);
        BlockingQueue<JsonNode> outsiderErrors = subscribe(outsiderSession, "/user/queue/errors");
        BlockingQueue<JsonNode> outsiderTopic = subscribe(outsiderSession, "/topic/rooms/" + code);

        JsonNode error = outsiderErrors.poll(5, TimeUnit.SECONDS);
        assertThat(error).isNotNull();
        assertThat(error.get("code").asText()).isEqualTo("NOT_PARTICIPANT");
        assertThat(error.at("/payload/destination").asText()).isEqualTo("/topic/rooms/" + code);

        outsiderSession.send("/app/rooms/" + code + "/ready", Map.of("ready", true));
        JsonNode sendError = outsiderErrors.poll(5, TimeUnit.SECONDS);
        assertThat(sendError.at("/payload/destination").asText()).isEqualTo("/app/rooms/" + code + "/ready");
        assertThat(outsiderSession.isConnected()).as("연결은 끊지 않는다").isTrue();

        // 방 사람의 준비 알림은 방 사람에게만 간다
        hostSession.send("/app/rooms/" + code + "/ready", Map.of("ready", true));
        JsonNode changed = hostTopic.poll(5, TimeUnit.SECONDS);
        assertThat(changed.get("type").asText()).isEqualTo("READY_CHANGED");
        assertThat(changed.at("/payload/userId").asLong()).isEqualTo(host.userId());
        assertThat(hostTopic.poll(500, TimeUnit.MILLISECONDS)).as("방 밖 사용자의 준비는 처리되지 않았다").isNull();
        assertThat(outsiderTopic.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    /**
     * T-31(보안 점검 H-2) 허용 목록. 깨지면: 방 코드만 알면 방 구독자에게 가짜 메시지를 방송하거나,
     * 남의 개인 큐로 가짜 RESYNC를 넣거나, 패턴 구독으로 남의 개인 큐를 엿들을 수 있다.
     */
    @Test
    void 허용_목록_밖의_발행과_구독은_무시하고_NOT_PARTICIPANT를_보낸다() throws Exception {
        Player host = guest("오션");
        String code = createRoom(host);
        Player outsider = guest("손님");

        StompSession hostSession = connect(host);
        BlockingQueue<JsonNode> hostTopic = subscribe(hostSession, "/topic/rooms/" + code);
        BlockingQueue<JsonNode> hostState = subscribe(hostSession, "/user/queue/state");
        BlockingQueue<JsonNode> hostErrors = subscribe(hostSession, "/user/queue/errors");
        StompSession outsiderSession = connect(outsider);
        BlockingQueue<JsonNode> outsiderErrors = subscribe(outsiderSession, "/user/queue/errors");
        Thread.sleep(300);
        hostState.clear(); // 구독 때 서버가 보낸 상태 메시지가 있으면 비운다

        // 가짜 방송: 방 topic으로 직접 발행
        String topic = "/topic/rooms/" + code;
        outsiderSession.send(topic, Map.of("type", "GAME_OVER", "roomCode", code));
        assertThat(rejected(outsiderErrors)).isEqualTo(topic);
        assertThat(hostTopic.poll(500, TimeUnit.MILLISECONDS)).as("가짜 방송은 방 구독자에게 가지 않는다").isNull();

        // 남의 개인 큐로 발행
        String hostQueue = "/user/" + host.userId() + "/queue/state";
        outsiderSession.send(hostQueue, Map.of("type", "RESYNC"));
        assertThat(rejected(outsiderErrors)).isEqualTo(hostQueue);
        assertThat(hostState.poll(500, TimeUnit.MILLISECONDS)).as("남의 개인 큐에 넣을 수 없다").isNull();

        // 패턴 구독과 남의 개인 큐 구독
        BlockingQueue<JsonNode> sniff = subscribe(outsiderSession, "/queue/**");
        assertThat(rejected(outsiderErrors)).isEqualTo("/queue/**");
        subscribe(outsiderSession, hostQueue);
        assertThat(rejected(outsiderErrors)).isEqualTo(hostQueue);
        assertThat(outsiderSession.isConnected()).as("연결은 끊지 않는다").isTrue();

        // 참가자라도 정해진 동작이 아니면 거부
        hostSession.send("/app/rooms/" + code + "/admin", Map.of());
        assertThat(rejected(hostErrors)).isEqualTo("/app/rooms/" + code + "/admin");

        // 참가자의 허용 목적지는 그대로 동작하고, 거부된 구독으로는 아무것도 새지 않는다
        hostSession.send("/app/rooms/" + code + "/ready", Map.of("ready", true));
        assertThat(nextOfType(hostTopic, "READY_CHANGED").at("/payload/userId").asLong()).isEqualTo(host.userId());
        assertThat(sniff.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    private String rejected(BlockingQueue<JsonNode> errors) throws InterruptedException {
        JsonNode error = errors.poll(5, TimeUnit.SECONDS);
        assertThat(error).as("NOT_PARTICIPANT 개인 오류").isNotNull();
        assertThat(error.get("code").asText()).isEqualTo("NOT_PARTICIPANT");
        return error.at("/payload/destination").asText();
    }

    @Test
    void 둘_다_준비하면_경기_행을_만들고_COUNTDOWN의_startsAt은_전송_시각_더하기_3초다() throws Exception {
        Player host = guest("오션");
        Player guest = guest("민수");
        String code = createRoom(host);
        joinRoom(guest, code);
        StompSession hostSession = connect(host);
        BlockingQueue<JsonNode> topic = subscribe(hostSession, "/topic/rooms/" + code);
        StompSession guestSession = connect(guest);
        subscribe(guestSession, "/topic/rooms/" + code);

        redis.expire(RedisKeys.room(code), Duration.ofMinutes(3));
        hostSession.send("/app/rooms/" + code + "/ready", Map.of("ready", true));
        assertThat(nextOfType(topic, "READY_CHANGED").at("/payload/ready").asBoolean()).isTrue();
        assertThat(redis.getExpire(RedisKeys.room(code))).as("준비는 활동이라 TTL 30분").isBetween(29 * 60L, 30 * 60L);

        hostSession.send("/app/rooms/" + code + "/ready", Map.of("ready", false));
        assertThat(nextOfType(topic, "READY_CHANGED").at("/payload/ready").asBoolean()).isFalse();
        hostSession.send("/app/rooms/" + code + "/ready", Map.of("ready", true));
        nextOfType(topic, "READY_CHANGED");
        guestSession.send("/app/rooms/" + code + "/ready", Map.of("ready", true));

        JsonNode countdown = nextOfType(topic, "COUNTDOWN");
        JsonNode p = countdown.get("payload");
        assertThat(p.get("startsAt").asLong() - countdown.get("sentAt").asLong()).isEqualTo(3000);
        assertThat(p.get("seriesNo").asInt()).isEqualTo(1);
        assertThat(p.get("roundNo").asInt()).isEqualTo(1);
        assertThat(p.get("attemptNo").asInt()).isEqualTo(1);
        long matchId = p.get("matchId").asLong();

        var match = jdbc.queryForMap("SELECT mode, status, seed, series_no, round_no, attempt_no, started_at FROM matches WHERE id = ?", matchId);
        assertThat(match).containsEntry("mode", "FRIEND").containsEntry("status", "PLAYING")
                .containsEntry("seed", p.get("seed").asLong()).containsEntry("series_no", 1);
        assertThat(jdbc.queryForObject("SELECT started_at FROM matches WHERE id = ?", java.sql.Timestamp.class, matchId)
                .toInstant().toEpochMilli()).as("started_at = startsAt").isEqualTo(p.get("startsAt").asLong());
        assertThat(jdbc.queryForList("SELECT slot, user_id FROM match_players WHERE match_id = ? ORDER BY slot", matchId))
                .containsExactly(Map.of("slot", 1, "user_id", host.userId()), Map.of("slot", 2, "user_id", guest.userId()));
        assertThat(jdbc.queryForObject("SELECT status FROM game_rooms WHERE room_code = ?", String.class, code)).isEqualTo("PLAYING");
        assertThat(redis.<String, String>opsForHash().entries(RedisKeys.room(code)))
                .containsEntry("status", "PLAYING").containsEntry("currentMatchId", Long.toString(matchId));
        assertThat(topic.poll(700, TimeUnit.MILLISECONDS)).as("COUNTDOWN은 한 번만").isNull();
    }

    /** 보안 점검 M-2. 깨지면: 서버가 하트비트를 보내지도 기대하지도 않아, TCP close 없이 사라진 연결의 끊김 유예·판정이 시작되지 않는다 */
    @Test
    void CONNECTED에_서버_하트비트_10초_25초를_알린다() throws Exception {
        Player p = guest("오션");
        CompletableFuture<StompHeaders> connected = new CompletableFuture<>();
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer " + p.token());
        sessions.add(client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connect,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession session, StompHeaders headers) {
                        connected.complete(headers);
                    }
                }).get(5, TimeUnit.SECONDS));
        assertThat(connected.get(5, TimeUnit.SECONDS).getHeartbeat()).containsExactly(10_000L, 25_000L);
    }

    /** 보안 점검 H-2 잔여. 깨지면: 방을 나간 사람이 구독을 유지한 채 그 방의 보드·준비·결과 방송을 계속 받는다 */
    @Test
    void 방을_나가면_그_방_구독이_지워져_이후_방송을_받지_않는다() throws Exception {
        Player host = guest("오션");
        Player guest = guest("민수");
        String code = createRoom(host);
        joinRoom(guest, code);
        StompSession hostSession = connect(host);
        BlockingQueue<JsonNode> hostTopic = subscribe(hostSession, "/topic/rooms/" + code);
        StompSession guestSession = connect(guest);
        BlockingQueue<JsonNode> guestTopic = subscribe(guestSession, "/topic/rooms/" + code);

        mvc.perform(post("/api/v1/rooms/" + code + "/leave").header("Authorization", "Bearer " + guest.token()));
        assertThat(nextOfType(hostTopic, "PLAYER_LEFT").at("/payload/userId").asLong()).isEqualTo(guest.userId());

        hostSession.send("/app/rooms/" + code + "/ready", Map.of("ready", true));
        nextOfType(hostTopic, "READY_CHANGED");
        assertThat(guestTopic.poll(500, TimeUnit.MILLISECONDS)).as("나간 뒤의 방송은 오지 않는다").isNull();
        assertThat(guestSession.isConnected()).as("연결 자체는 유지한다").isTrue();
    }

    private JsonNode nextOfType(BlockingQueue<JsonNode> queue, String type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode m = queue.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (m != null && type.equals(m.get("type").asText())) return m;
        }
        throw new AssertionError(type + " 메시지를 받지 못했다");
    }

    private void assertConnectRejected(String authorization, String expectedCode) throws Exception {
        CompletableFuture<StompHeaders> errorHeaders = new CompletableFuture<>();
        CompletableFuture<Object> errorBody = new CompletableFuture<>();
        StompHeaders connect = new StompHeaders();
        if (authorization != null) connect.add("Authorization", authorization);
        client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connect, new StompSessionHandlerAdapter() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                errorHeaders.complete(headers);
                errorBody.complete(payload);
            }
        });
        StompHeaders headers = errorHeaders.get(5, TimeUnit.SECONDS);
        assertThat(headers.getFirst("message")).isEqualTo(expectedCode);
        JsonNode body = (JsonNode) errorBody.get(1, TimeUnit.SECONDS);
        assertThat(body.get("code").asText()).isEqualTo(expectedCode);
        assertThat(body.get("message").asText()).isNotBlank();
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
        Thread.sleep(150); // 구독이 브로커에 등록될 시간
        return queue;
    }

    private Player guest(String nickname) throws Exception {
        JsonNode data = json.readTree(mvc.perform(post("/api/v1/auth/guest").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"" + nickname + "\"}")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
        return new Player(data.get("userId").asLong(), data.get("accessToken").asText());
    }

    private String createRoom(Player p) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/rooms").header("Authorization", "Bearer " + p.token())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getContentAsString())
                .at("/data/roomCode").asText();
    }

    private void joinRoom(Player p, String code) throws Exception {
        mvc.perform(post("/api/v1/rooms/" + code + "/join").header("Authorization", "Bearer " + p.token()));
    }
}
