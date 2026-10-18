package com.wellnessgame.support;

import com.wellnessgame.auth.JwtTokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * MockMvc 요청에 테스트용 Bearer 토큰을 붙이는 헬퍼.
 */
public final class TestAuth {
    private TestAuth() {
    }

    public static RequestPostProcessor bearer(JwtTokenService tokenService, String userId) {
        return bearerToken(tokenService.issue(userId).accessToken());
    }

    public static RequestPostProcessor bearerToken(String token) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }
}
