package com.wellnessgame.activity;

import com.wellnessgame.api.HealthActivitySyncRequest;
import com.wellnessgame.api.HealthActivitySyncRequest.ActivityPayload;
import com.wellnessgame.api.HealthActivitySyncResponse;
import com.wellnessgame.character.UserCharacterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class HealthActivitySyncServiceTest {
    @Autowired
    private HealthActivitySyncService syncService;

    @Autowired
    private HealthActivityRepository activityRepository;

    @Autowired
    private UserCharacterRepository characterRepository;

    @BeforeEach
    void cleanDatabase() {
        activityRepository.deleteAll();
        characterRepository.deleteAll();
    }

    @Test
    void preventsDuplicateXpAndStatRewards() {
        HealthActivitySyncRequest request = new HealthActivitySyncRequest(
                "test-user",
                LocalDate.of(2026, 6, 17),
                List.of(new ActivityPayload(
                        ActivityType.WORKOUT,
                        WorkoutType.RUNNING,
                        30,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Instant.parse("2026-06-17T08:00:00Z"),
                        Instant.parse("2026-06-17T08:30:00Z")
                ))
        );

        HealthActivitySyncResponse first = syncService.sync(request);
        HealthActivitySyncResponse second = syncService.sync(request);

        assertThat(first.gainedXp()).isEqualTo(70);
        assertThat(second.gainedXp()).isZero();
        assertThat(second.activityResults().get(0).duplicate()).isTrue();
        assertThat(second.character().totalXp()).isEqualTo(70);
        assertThat(second.character().stats().str()).isEqualTo(1);
        assertThat(second.character().stats().vit()).isEqualTo(1);
        assertThat(activityRepository.count()).isEqualTo(1);
    }

    @Test
    void handlesSeveralActivitiesAndLevelsUp() {
        HealthActivitySyncRequest request = new HealthActivitySyncRequest(
                "test-user",
                LocalDate.of(2026, 6, 17),
                List.of(
                        new ActivityPayload(ActivityType.STEPS, null, null, null, null, 16_000,
                                null, null, null, null),
                        new ActivityPayload(ActivityType.SLEEP, null, null, null, null, null,
                                450, 90,
                                Instant.parse("2026-06-16T22:30:00Z"),
                                Instant.parse("2026-06-17T06:00:00Z"))
                )
        );

        HealthActivitySyncResponse response = syncService.sync(request);

        assertThat(response.gainedXp()).isEqualTo(200);
        assertThat(response.levelUp()).isTrue();
        assertThat(response.character().level()).isEqualTo(2);
        assertThat(response.character().currentXp()).isEqualTo(100);
        assertThat(response.character().stats().discipline()).isEqualTo(1);
        assertThat(response.character().stats().recovery()).isEqualTo(1);
        assertThat(response.character().stats().vit()).isEqualTo(1);
    }
}

