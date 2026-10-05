package com.wellnessgame.config;

import com.wellnessgame.auth.AppleNativeProperties;
import com.wellnessgame.auth.AuthenticatedUserArgumentResolver;
import com.wellnessgame.auth.AuthenticationInterceptor;
import com.wellnessgame.auth.GoogleNativeProperties;
import com.wellnessgame.auth.JwtProperties;
import com.wellnessgame.auth.KakaoNativeProperties;
import com.wellnessgame.auth.LoginLockoutProperties;
import com.wellnessgame.auth.OAuthProperties;
import com.wellnessgame.ratelimit.RateLimitInterceptors;
import com.wellnessgame.ratelimit.RateLimitProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
@EnableConfigurationProperties({
        OAuthProperties.class,
        GoogleNativeProperties.class,
        KakaoNativeProperties.class,
        AppleNativeProperties.class,
        JwtProperties.class,
        LoginLockoutProperties.class,
        RateLimitProperties.class
})
public class WebConfig implements WebMvcConfigurer {
    private final String[] allowedOrigins;
    private final AuthenticationInterceptor authenticationInterceptor;
    private final AuthenticatedUserArgumentResolver authenticatedUserArgumentResolver;
    private final RateLimitInterceptors rateLimitInterceptors;

    public WebConfig(
            @Value("${cors.allowed-origins:http://localhost:5173}") String[] allowedOrigins,
            AuthenticationInterceptor authenticationInterceptor,
            AuthenticatedUserArgumentResolver authenticatedUserArgumentResolver,
            RateLimitInterceptors rateLimitInterceptors
    ) {
        this.allowedOrigins = allowedOrigins;
        this.authenticationInterceptor = authenticationInterceptor;
        this.authenticatedUserArgumentResolver = authenticatedUserArgumentResolver;
        this.rateLimitInterceptors = rateLimitInterceptors;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE)
                .exposedHeaders(HttpHeaders.WWW_AUTHENTICATE, HttpHeaders.RETRY_AFTER);
    }

    /**
     * /api/auth/** 를 제외한 모든 API 는 Bearer 토큰이 필요하다. /error 등 /api 밖 경로는 대상이 아니다.
     * 레이트 리밋(#48): 인증 엔드포인트는 IP 별로 인증 전에, 동기화는 userId 별로 인증 뒤에 센다(등록 순서 = 실행 순서).
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptors.auth()).addPathPatterns("/api/auth/**");
        registry.addInterceptor(rateLimitInterceptors.login()).addPathPatterns("/api/auth/login", "/api/auth/signup");
        registry.addInterceptor(authenticationInterceptor)
                .addPathPatterns("/api/**")
                // 새 /api/auth/** 보호 API가 조용히 공개되지 않도록 공개 경로를 명시한다.
                .excludePathPatterns(
                        "/api/auth/signup",
                        "/api/auth/login",
                        "/api/auth/google/native",
                        "/api/auth/kakao/native",
                        "/api/auth/naver/native",
                        "/api/auth/apple/native",
                        "/api/auth/{provider}");
        registry.addInterceptor(rateLimitInterceptors.sync()).addPathPatterns("/api/health-activities/sync");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(authenticatedUserArgumentResolver);
    }
}
