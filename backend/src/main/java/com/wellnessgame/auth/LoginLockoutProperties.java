package com.wellnessgame.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 아이디·비밀번호 로그인 잠금(#55).
 *
 * @param maxFailedLogins            같은 (아이디, 클라이언트 IP)로 연속 실패할 수 있는 횟수. 넘으면 그 조합만 잠근다.
 *                                   다른 IP 의 사용자는 영향받지 않아 타인이 피해자 계정을 쉽게 잠그지 못한다. 0 이하면 끈다.
 * @param maxFailedLoginsPerUsername IP 와 무관하게 한 아이디에 쌓일 수 있는 실패 횟수(분산 공격 방어). 넘으면 아이디 전체를 잠근다.
 *                                   0 이하면 끈다.
 * @param lockoutMinutes             잠금 시간이자 실패 기록을 기억하는 시간(마지막 실패 기준).
 */
@ConfigurationProperties(prefix = "app.auth")
public record LoginLockoutProperties(
        @DefaultValue("5") int maxFailedLogins,
        @DefaultValue("50") int maxFailedLoginsPerUsername,
        @DefaultValue("15") int lockoutMinutes
) {
    public static final LoginLockoutProperties DEFAULTS = new LoginLockoutProperties(5, 50, 15);

    public LoginLockoutProperties {
        if ((maxFailedLogins > 0 || maxFailedLoginsPerUsername > 0) && lockoutMinutes < 1) {
            throw new IllegalStateException("app.auth.lockout-minutes 는 1 이상이어야 합니다.");
        }
    }

    public Duration lockout() {
        return Duration.ofMinutes(lockoutMinutes);
    }
}
