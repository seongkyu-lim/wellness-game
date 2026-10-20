package com.wellnessgame.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 요청 빈도 제한(#48). 한도는 1분 고정 윈도우 기준이며 0 이하면 해당 제한을 끈다.
 *
 * @param syncPerMinute        {@code POST /api/health-activities/sync} 의 인증 주체(userId)별 한도
 * @param authPerMinute        {@code /api/auth/**} 전체의 클라이언트 IP별 한도
 * @param loginPerMinute       {@code /api/auth/login}·{@code /api/auth/signup} 의 클라이언트 IP별 한도(위 한도와 별도로 함께 적용)
 * @param refreshPerMinute     {@code /api/auth/refresh} 의 클라이언트 IP별 한도. 앱·웹이 자동으로 자주 부르므로 위 auth 한도와
 *                             따로 세고 더 넉넉하게 둔다(#102).
 * @param trustForwardedHeader true 면 {@code X-Forwarded-For} 에서 클라이언트 IP 를 고른다({@link ClientIpResolver}).
 *                             신뢰할 리버스 프록시 뒤에서만 켠다. 끄면(기본) 위조를 막기 위해 연결 주소만 쓴다.
 * @param trustedProxyHops     X-Forwarded-For 오른쪽에서 건너뛸 신뢰 프록시 값 수. 0 이면 마지막 값(바로 앞단 프록시가 붙인 주소).
 * @param logForwardedChain    true 면 hops 보정용으로 연결 주소와 X-Forwarded-For 원문을 10초에 한 번 INFO 로 남긴다.
 * @param maxKeys              레이트 리밋 카운터·로그인 실패 기록이 각각 메모리에 둘 최대 키 수. 넘으면 오래된 항목부터 내보낸다.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        @DefaultValue("30") int syncPerMinute,
        @DefaultValue("30") int authPerMinute,
        @DefaultValue("10") int loginPerMinute,
        @DefaultValue("120") int refreshPerMinute,
        @DefaultValue("false") boolean trustForwardedHeader,
        @DefaultValue("0") int trustedProxyHops,
        @DefaultValue("false") boolean logForwardedChain,
        @DefaultValue("100000") int maxKeys
) {
    public RateLimitProperties {
        if (maxKeys < 1) {
            throw new IllegalStateException("app.rate-limit.max-keys 는 1 이상이어야 합니다.");
        }
        if (trustedProxyHops < 0) {
            throw new IllegalStateException("app.rate-limit.trusted-proxy-hops 는 0 이상이어야 합니다.");
        }
    }
}
