package com.wellnessgame.ratelimit;

import com.wellnessgame.auth.AuthenticationInterceptor;
import com.wellnessgame.error.TooManyRequestsException;
import com.wellnessgame.i18n.Messages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * 경로별 레이트 리밋 인터셉터(#48). 등록 경로와 순서는 {@code WebConfig} 가 정한다.
 *
 * <p>스프링 MVC 는 CORS 인터셉터를 체인 맨 앞에 두므로, 여기서 429 를 던져도 CORS 응답 헤더는 이미 실려 있다.
 * CORS preflight(OPTIONS)는 세지 않는다.
 */
@Component
public class RateLimitInterceptors {
    static final Duration WINDOW = Duration.ofMinutes(1);

    private final RateLimitProperties properties;
    private final FixedWindowRateLimiter limiter;

    public RateLimitInterceptors(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.limiter = new FixedWindowRateLimiter(clock, WINDOW);
    }

    /** {@code /api/auth/**} 전체: 클라이언트 IP 별. */
    public HandlerInterceptor auth() {
        return interceptor("auth", RateLimitProperties::authPerMinute, this::clientIp);
    }

    /** 로그인·가입: 클라이언트 IP 별(더 엄격한 한도). */
    public HandlerInterceptor login() {
        return interceptor("login", RateLimitProperties::loginPerMinute, this::clientIp);
    }

    /** 동기화: 인증 주체(userId)별. {@link AuthenticationInterceptor} 다음에 등록해야 한다. POST 만 센다. */
    public HandlerInterceptor sync() {
        return interceptor("sync", RateLimitProperties::syncPerMinute, request -> {
            if (!HttpMethod.POST.matches(request.getMethod())) {
                return null;
            }
            Object userId = request.getAttribute(AuthenticationInterceptor.USER_ID_ATTRIBUTE);
            return userId == null ? null : userId.toString();
        });
    }

    private String clientIp(HttpServletRequest request) {
        return ClientIpResolver.resolve(request, properties.trustForwardedHeader());
    }

    /**
     * @param keyResolver null 을 돌려주면 이 요청은 세지 않는다.
     */
    private HandlerInterceptor interceptor(
            String scope,
            ToIntFunction<RateLimitProperties> limit,
            Function<HttpServletRequest, String> keyResolver
    ) {
        return new HandlerInterceptor() {
            @Override
            public boolean preHandle(
                    HttpServletRequest request,
                    HttpServletResponse response,
                    Object handler
            ) {
                int max = limit.applyAsInt(properties);
                if (max <= 0 || CorsUtils.isPreFlightRequest(request)) {
                    return true;
                }
                String key = keyResolver.apply(request);
                if (key == null) {
                    return true;
                }
                FixedWindowRateLimiter.Decision decision = limiter.tryAcquire(scope + ':' + key, max);
                if (!decision.allowed()) {
                    throw new TooManyRequestsException(
                            Messages.get("error.rate-limited", decision.retryAfterSeconds()),
                            decision.retryAfterSeconds());
                }
                return true;
            }
        };
    }

    int trackedKeys() {
        return limiter.trackedKeys();
    }
}
