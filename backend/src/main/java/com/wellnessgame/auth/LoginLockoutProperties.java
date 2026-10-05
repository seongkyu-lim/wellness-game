package com.wellnessgame.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 아이디·비밀번호 로그인 잠금(#55). 같은 아이디로 {@code maxFailedLogins} 회 연속 실패하면 {@code lockoutMinutes} 분 동안
 * 올바른 비밀번호도 거부한다. {@code maxFailedLogins} 가 0 이하면 잠금을 끈다.
 */
@ConfigurationProperties(prefix = "app.auth")
public record LoginLockoutProperties(
        @DefaultValue("5") int maxFailedLogins,
        @DefaultValue("15") int lockoutMinutes
) {
    public static final LoginLockoutProperties DEFAULTS = new LoginLockoutProperties(5, 15);

    public LoginLockoutProperties {
        if (maxFailedLogins > 0 && lockoutMinutes < 1) {
            throw new IllegalStateException("app.auth.lockout-minutes 는 1 이상이어야 합니다.");
        }
    }

    public boolean enabled() {
        return maxFailedLogins > 0;
    }

    public Duration lockout() {
        return Duration.ofMinutes(lockoutMinutes);
    }
}
