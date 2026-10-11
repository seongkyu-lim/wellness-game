package com.wellnessgame.auth;

import com.wellnessgame.i18n.Messages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

/**
 * 보호 API 요청의 {@code Authorization: Bearer <jwt>} 를 검증하고 주체·권한 범위를 요청 속성에 담는다.
 * 실패하면 {@link UnauthorizedException} 을 던져 ApiExceptionHandler 가 401 로 응답하게 한다.
 * CORS preflight 는 토큰 없이 통과시킨다.
 *
 * <p>조회 전용 토큰(scope=read, Watch #76)은 {@link #READ_ONLY_PATHS} 의 GET 요청만 허용하고 나머지
 * (동기화, watch-token 재발급, 로그아웃 등)는 403 이다. 허용 목록 방식이라 새 API 는 기본적으로 막힌다.
 */
@Component
public class AuthenticationInterceptor implements HandlerInterceptor {
    public static final String USER_ID_ATTRIBUTE = AuthenticationInterceptor.class.getName() + ".userId";
    public static final String SCOPE_ATTRIBUTE = AuthenticationInterceptor.class.getName() + ".scope";
    private static final String BEARER_PREFIX = "Bearer ";
    /** 조회 전용 토큰이 GET 으로 부를 수 있는 경로. */
    static final List<PathPattern> READ_ONLY_PATHS = List.of(
            PathPatternParser.defaultInstance.parse("/api/characters/{userId}"),
            PathPatternParser.defaultInstance.parse("/api/health-activities"));

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
            throw new UnauthorizedException(Messages.get("auth.login-required"));
        }
        if (!header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new UnauthorizedException(Messages.get("auth.token-invalid"));
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new UnauthorizedException(Messages.get("auth.token-invalid"));
        }
        AccessTokenPrincipal principal = tokenService.authenticate(token);
        if (principal.scope() == TokenScope.READ && !isReadOnlyRequest(request)) {
            throw new ForbiddenException(Messages.get("auth.read-only-token"));
        }
        request.setAttribute(USER_ID_ATTRIBUTE, principal.userId());
        request.setAttribute(SCOPE_ATTRIBUTE, principal.scope());
        return true;
    }

    private static boolean isReadOnlyRequest(HttpServletRequest request) {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        PathContainer container = PathContainer.parsePath(path);
        return READ_ONLY_PATHS.stream().anyMatch(pattern -> pattern.matches(container));
    }
}
