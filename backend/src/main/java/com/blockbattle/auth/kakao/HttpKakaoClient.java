package com.blockbattle.auth.kakao;

import com.blockbattle.common.error.ApiException;
import com.blockbattle.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 카카오 REST: 인가 코드 → 토큰(kauth.kakao.com/oauth/token) → 사용자 ID(kapi.kakao.com/v2/user/me).
 * 앱 키는 KAKAO_CLIENT_ID·KAKAO_CLIENT_SECRET 환경 변수로 받는다.
 */
@Component
public class HttpKakaoClient implements KakaoClient {

    private final RestClient rest = RestClient.create();
    private final String clientId;
    private final String clientSecret;

    public HttpKakaoClient(@Value("${app.kakao.client-id:}") String clientId,
                           @Value("${app.kakao.client-secret:}") String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public String userIdFor(String authorizationCode, String redirectUri) {
        if (isBlank(authorizationCode) || isBlank(redirectUri) || isBlank(clientId)) {
            throw new ApiException(ErrorCode.KAKAO_AUTH_FAILED);
        }
        try {
            var form = new LinkedMultiValueMap<String, String>();
            form.add("grant_type", "authorization_code");
            form.add("client_id", clientId);
            form.add("redirect_uri", redirectUri);
            form.add("code", authorizationCode);
            if (!isBlank(clientSecret)) form.add("client_secret", clientSecret);
            JsonNode token = rest.post().uri("https://kauth.kakao.com/oauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
            String accessToken = token == null ? null : token.path("access_token").asText(null);
            if (accessToken == null) throw new ApiException(ErrorCode.KAKAO_AUTH_FAILED);
            JsonNode me = rest.get().uri("https://kapi.kakao.com/v2/user/me")
                    .header("Authorization", "Bearer " + accessToken).retrieve().body(JsonNode.class);
            if (me == null || !me.hasNonNull("id")) throw new ApiException(ErrorCode.KAKAO_AUTH_FAILED);
            return me.get("id").asText();
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.KAKAO_AUTH_FAILED);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
