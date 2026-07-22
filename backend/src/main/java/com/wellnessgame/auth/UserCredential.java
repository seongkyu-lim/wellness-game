package com.wellnessgame.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * 아이디/비밀번호 로그인 계정. 다른 도메인은 iOS 앱이 만드는 "password:{식별자}" 문자열로
 * 사용자를 구분하므로, 여기서는 그 식별자와 BCrypt 해시만 보관한다.
 */
@Entity
@Table(name = "user_credential", uniqueConstraints = {
        @UniqueConstraint(name = "uk_credential_username", columnNames = "username"),
        @UniqueConstraint(name = "uk_credential_user_identifier", columnNames = "user_identifier")
})
public class UserCredential {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false, length = 32)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "user_identifier", nullable = false, updatable = false, length = 64)
    private String userIdentifier;

    @Column(name = "display_name", length = 64)
    private String displayName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserCredential() {
    }

    public UserCredential(String username, String passwordHash, String userIdentifier, String displayName) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.userIdentifier = userIdentifier;
        this.displayName = displayName;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getUserIdentifier() {
        return userIdentifier;
    }

    public String getDisplayName() {
        return displayName;
    }
}
