package com.wellnessgame.activity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HealthActivityRepository extends JpaRepository<HealthActivity, UUID> {
    boolean existsByExternalKey(String externalKey);

    Optional<HealthActivity> findByExternalKey(String externalKey);

    List<HealthActivity> findByUserIdAndActivityDate(String userId, LocalDate activityDate);

    /**
     * 같은 사용자·유형의 기존 로그 중 [startedAt, endedAt) 구간과 겹치는 것이 있는지 확인한다.
     * 경계가 맞닿는 경우(한쪽 종료 == 다른쪽 시작)는 겹침으로 보지 않는다.
     */
    @Query("""
            select count(a) > 0 from HealthActivity a
            where a.userId = :userId
              and a.type = :type
              and a.startedAt < :endedAt
              and a.endedAt > :startedAt
            """)
    boolean existsOverlapping(
            @Param("userId") String userId,
            @Param("type") ActivityType type,
            @Param("startedAt") Instant startedAt,
            @Param("endedAt") Instant endedAt
    );

    /**
     * 사용자의 특정 날짜 지급 XP 합계(일일 상한 계산용).
     */
    @Query("""
            select coalesce(sum(a.gainedXp), 0) from HealthActivity a
            where a.userId = :userId and a.activityDate = :activityDate
            """)
    long sumGainedXpByUserIdAndActivityDate(
            @Param("userId") String userId,
            @Param("activityDate") LocalDate activityDate
    );
}
