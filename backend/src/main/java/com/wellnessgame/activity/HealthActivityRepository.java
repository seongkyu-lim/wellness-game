package com.wellnessgame.activity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface HealthActivityRepository extends JpaRepository<HealthActivity, UUID> {
    boolean existsByExternalKey(String externalKey);
}

