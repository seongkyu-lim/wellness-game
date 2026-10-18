package com.wellnessgame.auth;

import com.wellnessgame.WellnessGameApplication;
import com.wellnessgame.support.MutableClock;
import com.wellnessgame.support.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** user_auth_state 네이티브 upsert(on conflict do nothing + greatest 갱신)가 H2 PostgreSQL 모드에서도 동작하는지 확인한다. */
@SpringBootTest(classes = WellnessGameApplication.class)
@Import(TestClockConfig.class)
class TokenValidityUpsertTest {
    @Autowired
    private TokenValidityService tokenValidityService;

    @Autowired
    private UserAuthStateRepository repository;

    @Autowired
    private MutableClock clock;

    @Test
    void createsThenAdvancesButNeverMovesBack() {
        String user = "upsert-user";
        clock.setInstant(Instant.parse("2026-06-17T10:00:00.900Z"));
        tokenValidityService.revokeIssuedTokens(user);
        assertThat(validAfter(user)).isEqualTo(Instant.parse("2026-06-17T10:00:00Z"));

        clock.setInstant(Instant.parse("2026-06-17T11:00:00Z"));
        tokenValidityService.revokeIssuedTokens(user);
        assertThat(validAfter(user)).isEqualTo(Instant.parse("2026-06-17T11:00:00Z"));

        clock.setInstant(Instant.parse("2026-06-17T09:00:00Z"));
        tokenValidityService.revokeIssuedTokens(user);
        assertThat(validAfter(user)).isEqualTo(Instant.parse("2026-06-17T11:00:00Z"));
        assertThat(tokenValidityService.isRevoked(user, Instant.parse("2026-06-17T10:59:59Z"))).isTrue();
    }

    private Instant validAfter(String user) {
        return repository.findById(user).orElseThrow().getTokensValidAfter();
    }
}
