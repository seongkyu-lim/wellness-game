package com.wellnessgame.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 요청 빈도 제한(#48). 한도는 1분 고정 윈도우 기준이며 0 이하면 해당 제한을 끈다.
 *
 * @param syncPerMinute        {@code POST /api/health-activities/sync} 의 인증 주체(userId)별 한도
 * @param authPerMinute        {@code /api/auth/**} 전체의 클라이언트 IP별 한도
 * @param loginPerMinute       {@code /api/auth/login}·{@code /api/auth/signup} 의 클라이언트 IP별 한도(위 한도와 별도로 함께 적용)
 * @param trustForwardedHeader true 면 {@code X-Forwarded-For} 의 마지막(가장 가까운 프록시가 붙인) 주소를 클라이언트 IP 로 쓴다.
 *                             신뢰할 리버스 프록시 뒤에서만 켠다. 끄면(기본) 위조를 막기 위해 연결 주소만 쓴다.
 * @param maxKeys              레이트 리밋 카운터·로그인 실패 기록이 각각 메모리에 둘 최대 키 수. 넘으면 오래된 항목부터 내보낸다.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        @DefaultValue("30") int syncPerMinute,
        @DefaultValue("30") int authPerMinute,
        @DefaultValue("10") int loginPerMinute,
        @DefaultValue("false") boolean trustForwardedHeader,
        @DefaultValue("100000") int maxKeys
) {
    public RateLimitProperties {
        if (maxKeys < 1) {
            throw new IllegalStateException("app.rate-limit.max-keys 는 1 이상이어야 합니다.");
        }
    }
}
