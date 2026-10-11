package com.wellnessgame.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 사용자별 액세스 토큰 무효화 기준(V3). iat 가 tokensValidAfter 보다 이전인 토큰은 거부한다. */
@Entity
@Table(name = "user_auth_state")
public class UserAuthState {
    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private String userId;

    @Column(name = "tokens_valid_after", nullable = false)
    private Instant tokensValidAfter;

    protected UserAuthState() {
    }

    UserAuthState(String userId, Instant tokensValidAfter) {
        this.userId = userId;
        this.tokensValidAfter = tokensValidAfter;
    }

    /** 기준을 앞으로만 옮긴다. */
    void advanceTo(Instant instant) {
        if (instant.isAfter(tokensValidAfter)) {
            tokensValidAfter = instant;
        }
    }

    public String getUserId() {
        return userId;
    }

    public Instant getTokensValidAfter() {
        return tokensValidAfter;
    }
}
