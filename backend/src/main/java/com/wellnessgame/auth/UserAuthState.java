package com.wellnessgame.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 사용자별 액세스 토큰 무효화 기준(V3, V5). iat 가 tokensValidAfter 보다 이전인 토큰은 거부하고, 조회 전용(scope=read) 토큰은
 * readTokensValidAfter 도 함께 본다({@link TokenValidityService}).
 * 쓰기는 동시 요청에도 안전한 네이티브 upsert({@link UserAuthStateRepository#insertIfAbsent}, {@link UserAuthStateRepository#advanceTo}, {@link UserAuthStateRepository#advanceReadTo})로만 한다.
 */
@Entity
@Table(name = "user_auth_state")
public class UserAuthState {
    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private String userId;

    @Column(name = "tokens_valid_after", nullable = false)
    private Instant tokensValidAfter;

    /** 조회 전용 토큰만의 무효화 기준(#103). 없으면 null. */
    @Column(name = "read_tokens_valid_after")
    private Instant readTokensValidAfter;

    protected UserAuthState() {
    }

    /** 테스트용. 운영 코드의 쓰기는 네이티브 upsert 로만 한다. */
    UserAuthState(String userId, Instant tokensValidAfter, Instant readTokensValidAfter) {
        this.userId = userId;
        this.tokensValidAfter = tokensValidAfter;
        this.readTokensValidAfter = readTokensValidAfter;
    }

    public String getUserId() {
        return userId;
    }

    public Instant getTokensValidAfter() {
        return tokensValidAfter;
    }

    public Instant getReadTokensValidAfter() {
        return readTokensValidAfter;
    }
}
