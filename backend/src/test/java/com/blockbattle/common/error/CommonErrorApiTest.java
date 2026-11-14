package com.blockbattle.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.blockbattle.support.ContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * POL-026, API 명세 6장 NOT_FOUND·METHOD_NOT_ALLOWED.
 * 깨지면: 없는 주소·잘못된 메서드가 다시 500 서버 오류로 집계되거나, 인증 검사보다 먼저 404가 나가 보호 경로가 드러난다는 뜻이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ContainersConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class CommonErrorApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void 매핑되지_않은_경로는_404_NOT_FOUND이고_서버_오류로_기록하지_않는다(CapturedOutput output) throws Exception {
        String token = guestToken();
        MockHttpServletResponse inApi = mvc.perform(get("/api/v1/nope").header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        MockHttpServletResponse outside = mvc.perform(get("/actuator/health")).andReturn().getResponse();

        expectError(inApi, 404, "NOT_FOUND");
        expectError(outside, 404, "NOT_FOUND");
        assertThat(output.getOut() + output.getErr()).doesNotContain("처리하지 못한 예외");
    }

    @Test
    void 지원하지_않는_메서드는_405_METHOD_NOT_ALLOWED와_Allow_헤더다(CapturedOutput output) throws Exception {
        String token = guestToken();
        MockHttpServletResponse res = mvc.perform(delete("/api/v1/auth/guest").header("Authorization", "Bearer " + token))
                .andReturn().getResponse();

        expectError(res, 405, "METHOD_NOT_ALLOWED");
        assertThat(res.getHeader("Allow")).contains("POST");
        assertThat(output.getOut() + output.getErr()).doesNotContain("처리하지 못한 예외");
    }

    @Test
    void 인증_대상_경로는_없는_주소여도_토큰이_없으면_401이_먼저다() throws Exception {
        expectError(mvc.perform(get("/api/v1/nope")).andReturn().getResponse(), 401, "UNAUTHORIZED");
    }

    private void expectError(MockHttpServletResponse res, int status, String code) throws Exception {
        assertThat(res.getStatus()).isEqualTo(status);
        JsonNode body = json.readTree(res.getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("success").asBoolean()).isFalse();
        assertThat(body.get("data").isNull()).isTrue();
        assertThat(body.at("/error/code").asText()).isEqualTo(code);
    }

    private String guestToken() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/guest").contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"주소확인\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body).at("/data/accessToken").asText();
    }
}
