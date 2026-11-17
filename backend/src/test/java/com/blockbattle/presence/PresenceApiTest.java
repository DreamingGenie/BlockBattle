package com.blockbattle.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.blockbattle.store.redis.RedisKeys;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T-49 계획의 heartbeat 계약을 대조한다.
 * 깨지면: 로비의 '플레이 중 N명'이 같은 사람을 여러 번 세거나, 떠난 사람을 계속 세거나, 경기를 끝낸 사람을 플레이 중으로 센다는 뜻이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ContainersConfig.class)
class PresenceApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void clean() {
        redis.delete(RedisKeys.PRESENCE_ONLINE);
        redis.delete(RedisKeys.PRESENCE_PLAYING);
    }

    @Test
    void 토큰_없이_보내면_401() throws Exception {
        assertThat(mvc.perform(post("/api/v1/presence/heartbeat")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void 같은_사용자가_여러_탭에서_보내도_1명이고_경기를_끝내면_플레이_중에서_빠진다() throws Exception {
        String a = token("가람");
        String b = token("나래");

        beat(a, true);
        beat(a, true); // 다른 탭
        JsonNode counts = beat(b, false);
        assertThat(counts.get("online").asLong()).isEqualTo(2);
        assertThat(counts.get("playing").asLong()).isEqualTo(1);

        counts = beat(a, false);
        assertThat(counts.get("online").asLong()).isEqualTo(2);
        assertThat(counts.get("playing").asLong()).isEqualTo(0);
    }

    @Test
    void 마지막_heartbeat가_70초보다_오래된_사람은_두_수에서_모두_빠진다() throws Exception {
        long stale = System.currentTimeMillis() - RedisKeys.PRESENCE_WINDOW.toMillis() - 1_000;
        redis.opsForZSet().add(RedisKeys.PRESENCE_ONLINE, "999999", stale);
        redis.opsForZSet().add(RedisKeys.PRESENCE_PLAYING, "999999", stale);

        JsonNode counts = beat(token("다온"), true);
        assertThat(counts.get("online").asLong()).isEqualTo(1);
        assertThat(counts.get("playing").asLong()).isEqualTo(1);
        assertThat(redis.opsForZSet().score(RedisKeys.PRESENCE_ONLINE, "999999")).isNull();
    }

    private JsonNode beat(String token, boolean playing) throws Exception {
        MockHttpServletResponse res = mvc.perform(post("/api/v1/presence/heartbeat").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"playing\":" + playing + "}")).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        return json.readTree(res.getContentAsString(StandardCharsets.UTF_8)).get("data");
    }

    private String token(String nickname) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/auth/guest").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"" + nickname + "\"}")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).get("data").get("accessToken").asText();
    }
}
