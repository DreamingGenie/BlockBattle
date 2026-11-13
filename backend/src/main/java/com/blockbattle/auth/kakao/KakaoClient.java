package com.blockbattle.auth.kakao;

/** 카카오 인가 코드를 카카오 사용자 ID로 바꾼다. 실패하면 KAKAO_AUTH_FAILED(ApiException). */
public interface KakaoClient {

    String userIdFor(String authorizationCode, String redirectUri);
}
