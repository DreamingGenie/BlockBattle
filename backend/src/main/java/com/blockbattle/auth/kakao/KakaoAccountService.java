package com.blockbattle.auth.kakao;

import com.blockbattle.common.error.ApiException;
import com.blockbattle.common.error.ErrorCode;
import com.blockbattle.user.UserRepository;
import com.blockbattle.user.UserRepository.UserRow;
import java.time.Clock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카카오 연결·로그인(FR-USR-02, REQ-002). 연결해도 userId와 전적은 그대로이고 user_type만 MEMBER가 된다.
 * 연결되지 않은 카카오 계정으로는 새 계정을 만들지 않는다.
 */
@Service
public class KakaoAccountService {

    public static final String PROVIDER = "KAKAO";

    private final KakaoClient kakao;
    private final UserRepository users;
    private final Clock clock;

    public KakaoAccountService(KakaoClient kakao, UserRepository users, Clock clock) {
        this.kakao = kakao;
        this.users = users;
        this.clock = clock;
    }

    @Transactional
    public UserRow link(long userId, String authorizationCode, String redirectUri) {
        String kakaoId = kakao.userIdFor(authorizationCode, redirectUri);
        UserRow me = users.findForUpdate(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        if (me.provider() != null) throw new ApiException(ErrorCode.ACCOUNT_ALREADY_LINKED);
        if (users.findByProvider(PROVIDER, kakaoId).isPresent()) throw new ApiException(ErrorCode.KAKAO_ALREADY_LINKED);
        try {
            users.linkProvider(userId, PROVIDER, kakaoId);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.KAKAO_ALREADY_LINKED);
        }
        return new UserRow(me.id(), me.nickname(), me.tag(), "MEMBER", PROVIDER);
    }

    @Transactional
    public UserRow login(String authorizationCode, String redirectUri) {
        String kakaoId = kakao.userIdFor(authorizationCode, redirectUri);
        UserRow user = users.findByProvider(PROVIDER, kakaoId).orElseThrow(() -> new ApiException(ErrorCode.KAKAO_NOT_LINKED));
        users.touchLastLogin(user.id(), clock.instant());
        return user;
    }
}
