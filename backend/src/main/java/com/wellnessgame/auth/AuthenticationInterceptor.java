package com.wellnessgame.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 보호 API 요청의 {@code Authorization: Bearer <jwt>} 를 검증하고 주체를 요청 속성에 담는다.
 * 실패하면 {@link UnauthorizedException} 을 던져 ApiExceptionHandler 가 401 로 응답하게 한다.
 * CORS preflight 는 토큰 없이 통과시킨다.
 */
@Component
public class AuthenticationInterceptor implements HandlerInterceptor {
    public static final String USER_ID_ATTRIBUTE = AuthenticationInterceptor.class.getName() + ".userId";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService tokenService;

    public AuthenticationInterceptor(JwtTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.isBlank()) {
            throw new UnauthorizedException("인증이 필요합니다. 로그인해 주세요.");
        }
        if (!header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new UnauthorizedException("토큰이 유효하지 않습니다.");
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new UnauthorizedException("토큰이 유효하지 않습니다.");
        }
        request.setAttribute(USER_ID_ATTRIBUTE, tokenService.verify(token));
        return true;
    }
}
