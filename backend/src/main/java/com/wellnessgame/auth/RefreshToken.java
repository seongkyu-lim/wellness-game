package com.wellnessgame.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * 회전 리프레시 토큰 한 건(V3__refresh_tokens). 토큰 원문 대신 SHA-256 hex 만 저장한다.
 * 회전은 행 잠금({@link RefreshTokenRepository#findForUpdateByTokenHash})을 잡은 채 {@link #markRotated} 로 한다.
 */
@Entity
@Table(name = "refresh_token",
        uniqueConstraints = @UniqueConstraint(name = "uk_refresh_token_hash", columnNames = "token_hash"),
        indexes = {
                @Index(name = "idx_refresh_token_family", columnList = "family_id"),
                @Index(name = "idx_refresh_token_user", columnList = "user_id"),
                @Index(name = "idx_refresh_token_expires", columnList = "expires_at")
        })
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private String userId;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "family_created_at", nullable = false, updatable = false)
    private Instant familyCreatedAt;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    protected RefreshToken() {
    }

    RefreshToken(String userId, UUID familyId, Instant familyCreatedAt, String tokenHash, Instant issuedAt,
                 Instant expiresAt) {
        this.userId = userId;
        this.familyId = familyId;
        this.familyCreatedAt = familyCreatedAt;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    /** 회전으로 폐기한다. replacedBy 는 첫 후계 토큰이다(유예 구간의 형제 토큰은 기록하지 않음). */
    void markRotated(Instant now, UUID successorId) {
        this.revokedAt = now;
        this.replacedBy = successorId;
    }

    public UUID getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public Instant getFamilyCreatedAt() {
        return familyCreatedAt;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getReplacedBy() {
        return replacedBy;
    }
}
