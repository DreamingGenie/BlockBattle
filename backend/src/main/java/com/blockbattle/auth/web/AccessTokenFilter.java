package com.blockbattle.auth.web;

import com.blockbattle.auth.jwt.JwtProvider;
import com.blockbattle.common.api.ApiResponse;
import com.blockbattle.common.error.ApiException;
import com.blockbattle.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * REST 요청의 Access JWT 인증(REQ-018). 인증 예외(게스트 로그인·카카오 로그인)와
 * Refresh 쿠키로 인증하는 재발급을 뺀 /api/v1/** 요청에 Bearer 토큰을 요구한다.
 */
@Component
public class AccessTokenFilter extends OncePerRequestFilter {

    /** 컨트롤러가 @RequestAttribute(USER_ID)로 읽는다 */
    public static final String USER_ID = "authUserId";
    public static final String API_PREFIX = "/api/v1";

    private static final Set<String> PUBLIC_POSTS = Set.of(
            API_PREFIX + "/auth/guest", API_PREFIX + "/auth/kakao/login", API_PREFIX + "/auth/refresh");

    private final JwtProvider jwt;
    private final ObjectMapper json;

    public AccessTokenFilter(JwtProvider jwt, ObjectMapper json) {
        this.jwt = jwt;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith(API_PREFIX + "/")) return true;
        return "POST".equals(request.getMethod()) && PUBLIC_POSTS.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            reject(response, ErrorCode.UNAUTHORIZED);
            return;
        }
        try {
            request.setAttribute(USER_ID, jwt.verify(header.substring("Bearer ".length()).trim()));
        } catch (ApiException e) {
            reject(response, e.code());
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(response.getWriter(), ApiResponse.fail(code.name(), code.message()));
    }
}
