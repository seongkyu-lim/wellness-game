package com.wellnessgame.activity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HealthActivityRepository extends JpaRepository<HealthActivity, UUID> {
    boolean existsByExternalKey(String externalKey);

    Optional<HealthActivity> findByExternalKey(String externalKey);

    List<HealthActivity> findByUserIdAndActivityDate(String userId, LocalDate activityDate);
}

