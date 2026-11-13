package com.blockbattle.auth;

import com.blockbattle.common.error.ApiException;
import com.blockbattle.common.error.ErrorCode;
import com.blockbattle.store.redis.RefreshTokenStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Refresh 토큰 발급·회전·폐기(FR-USR-01). 토큰은 "userId.무작위 32바이트" 불투명 문자열이고,
 * Redis refresh:{userId}에는 토큰 전체의 SHA-256 해시만 둔다(ENT-013).
 */
@Component
public class RefreshTokens {

    public record Rotated(long userId, String token) {
    }

    private final RefreshTokenStore store;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokens(RefreshTokenStore store) {
        this.store = store;
    }

    public String issue(long userId) {
        String token = newToken(userId);
        store.replace(userId, sha256(token));
        return token;
    }

    /** 쿠키 없음·형식 오류·만료(키 없음)·폐기·이미 쓴 토큰은 모두 TOKEN_EXPIRED */
    public Rotated rotate(String presented) {
        long userId = userIdOf(presented);
        String next = newToken(userId);
        if (!store.rotateIfMatches(userId, sha256(presented), sha256(next))) {
            throw new ApiException(ErrorCode.TOKEN_EXPIRED);
        }
        return new Rotated(userId, next);
    }

    public void revoke(long userId) {
        store.revoke(userId);
    }

    private long userIdOf(String token) {
        if (token == null) throw new ApiException(ErrorCode.TOKEN_EXPIRED);
        int dot = token.indexOf('.');
        try {
            return Long.parseLong(token.substring(0, dot));
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.TOKEN_EXPIRED);
        }
    }

    private String newToken(long userId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return userId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
