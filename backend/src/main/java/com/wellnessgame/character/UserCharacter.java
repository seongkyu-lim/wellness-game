package com.wellnessgame.character;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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

@Entity
@Table(name = "user_character", uniqueConstraints = @UniqueConstraint(name = "uk_character_user", columnNames = "user_id"))
public class UserCharacter {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private String userId;

    @Column(nullable = false)
    private int level;

    @Column(name = "current_xp", nullable = false)
    private int currentXp;

    @Column(name = "total_xp", nullable = false)
    private int totalXp;

    @Embedded
    private CharacterStats stats;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserCharacter() {
    }

    public UserCharacter(String userId) {
        this.userId = userId;
        this.level = 1;
        this.stats = CharacterStats.initial();
    }

    public boolean addXp(int gainedXp) {
        if (gainedXp <= 0) {
            return false;
        }
        totalXp += gainedXp;
        currentXp += gainedXp;
        boolean leveledUp = false;
        while (currentXp >= nextLevelXp()) {
            currentXp -= nextLevelXp();
            level += 1;
            leveledUp = true;
        }
        return leveledUp;
    }

    public int nextLevelXp() {
        return 100 + (level - 1) * 50;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public int getLevel() {
        return level;
    }

    public int getCurrentXp() {
        return currentXp;
    }

    public int getTotalXp() {
        return totalXp;
    }

    public CharacterStats getStats() {
        return stats;
    }
}
