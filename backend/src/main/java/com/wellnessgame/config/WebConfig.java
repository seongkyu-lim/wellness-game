package com.wellnessgame.config;

import com.wellnessgame.auth.AuthenticatedUserArgumentResolver;
import com.wellnessgame.auth.AuthenticationInterceptor;
import com.wellnessgame.auth.GoogleNativeProperties;
import com.wellnessgame.auth.JwtProperties;
import com.wellnessgame.auth.OAuthProperties;
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
@EnableConfigurationProperties({OAuthProperties.class, GoogleNativeProperties.class, JwtProperties.class})
public class WebConfig implements WebMvcConfigurer {
    private final String[] allowedOrigins;
    private final AuthenticationInterceptor authenticationInterceptor;
    private final AuthenticatedUserArgumentResolver authenticatedUserArgumentResolver;

    public WebConfig(
            @Value("${cors.allowed-origins:http://localhost:5173}") String[] allowedOrigins,
            AuthenticationInterceptor authenticationInterceptor,
            AuthenticatedUserArgumentResolver authenticatedUserArgumentResolver
    ) {
        this.allowedOrigins = allowedOrigins;
        this.authenticationInterceptor = authenticationInterceptor;
        this.authenticatedUserArgumentResolver = authenticatedUserArgumentResolver;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE)
                .exposedHeaders(HttpHeaders.WWW_AUTHENTICATE);
    }

    /** /api/auth/** 를 제외한 모든 API 는 Bearer 토큰이 필요하다. /error 등 /api 밖 경로는 대상이 아니다. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authenticationInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(authenticatedUserArgumentResolver);
    }
}
