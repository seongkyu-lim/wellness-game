package com.wellnessgame.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 모든 응답에 보안 헤더를 붙인다(#104). 체인 실행 전에 넣어 오류 응답(401·429·500 등)에도 실린다.
 *
 * <ul>
 *   <li>HSTS: Render 는 HTTPS 를 프록시에서 끝내므로 요청 스킴과 상관없이 붙인다(HTTP 응답의 HSTS 는 브라우저가 무시).</li>
 *   <li>CSP: API 는 HTML 을 내지 않으므로 프레임 금지만 둔다. Swagger UI(/swagger-ui/**)는 같은 출처의 스크립트·스타일과
 *       data: 이미지를 허용하는 정책을 쓴다.</li>
 *   <li>CORS 헤더는 스프링 MVC 가 따로 붙이므로 영향이 없다.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {
    static final String HSTS = "max-age=31536000; includeSubDomains";
    static final String API_CSP = "frame-ancestors 'none'";
    static final String SWAGGER_CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; connect-src 'self'; font-src 'self' data:; object-src 'none'; base-uri 'self'; "
            + "form-action 'self'; frame-ancestors 'none'";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Strict-Transport-Security", HSTS);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", isSwaggerUi(request) ? SWAGGER_CSP : API_CSP);
        chain.doFilter(request, response);
    }

    /** 오류 디스패치에도 헤더를 다시 확인해 붙인다. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    private static boolean isSwaggerUi(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/swagger-ui/") || path.equals("/swagger-ui.html");
    }
}
