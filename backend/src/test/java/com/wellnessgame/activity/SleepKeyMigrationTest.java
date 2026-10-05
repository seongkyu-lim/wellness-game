package com.wellnessgame.activity;

import com.wellnessgame.api.HealthActivitySyncRequest;
import com.wellnessgame.api.HealthActivitySyncResponse;
import com.wellnessgame.character.UserCharacterRepository;
import com.wellnessgame.support.MutableClock;
import com.wellnessgame.support.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.wellnessgame.support.ActivityPayloads.sleep;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #50 구 형식 수면 로그 키(userId|start|end|SLEEP) 정리 마이그레이션.
 */
@SpringBootTest
@Import(TestClockConfig.class)
class SleepKeyMigrationTest {
    private static final String USER = "sleep-migration-user";
    private static final LocalDate DAY = LocalDate.of(2026, 6, 17);
    private static final String NEW_KEY = USER + "|" + DAY + "|SLEEP";

    @Autowired
    private SleepKeyMigration migration;

    @Autowired
    private HealthActivitySyncService syncService;

    @Autowired
    private HealthActivityRepository activityRepository;

    @Autowired
    private UserCharacterRepository characterRepository;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        activityRepository.deleteAll();
        characterRepository.deleteAll();
        clock.setInstant(Instant.parse("2026-06-17T23:00:00Z"));
    }

    @Test
    void promotesLongestLegacySleepAndArchivesTheRestSoResyncGrantsNothing() {
        HealthActivity shortNap = saveSleep(oldKey("2026-06-17T01:00:00Z", "2026-06-17T06:00:00Z"), 300, 25);
        HealthActivity night = saveSleep(oldKey("2026-06-16T22:00:00Z", "2026-06-17T05:30:00Z"), 450, 80);

        migration.run(null);

        Map<UUID, String> keys = keysById();
        assertThat(keys.get(night.getId())).isEqualTo(NEW_KEY);
        assertThat(keys.get(shortNap.getId()))
                .isEqualTo(oldKey("2026-06-17T01:00:00Z", "2026-06-17T06:00:00Z") + "|legacy|" + shortNap.getId());
        assertThat(activityRepository.count()).isEqualTo(2); // 삭제 없음
        assertThat(activityRepository.sumGainedXpByUserIdAndActivityDate(USER, DAY)).isEqualTo(105);

        HealthActivitySyncResponse resync = syncService.sync(new HealthActivitySyncRequest(USER, DAY,
                List.of(sleep(450, 90, "2026-06-16T22:00:00Z", "2026-06-17T05:30:00Z"))));

        assertThat(resync.gainedXp()).isZero();
        assertThat(resync.activityResults().get(0).duplicate()).isTrue();
        assertThat(activityRepository.count()).isEqualTo(2);
        assertThat(activityRepository.sumGainedXpByUserIdAndActivityDate(USER, DAY)).isEqualTo(105);
    }

    @Test
    void tieOnSleepMinutesPromotesEarliestCreated() throws InterruptedException {
        HealthActivity earlier = saveSleep(oldKey("2026-06-16T22:00:00Z", "2026-06-17T05:00:00Z"), 420, 60);
        Thread.sleep(5);
        HealthActivity later = saveSleep(oldKey("2026-06-16T23:00:00Z", "2026-06-17T06:00:00Z"), 420, 60);

        migration.run(null);

        Map<UUID, String> keys = keysById();
        assertThat(keys.get(earlier.getId())).isEqualTo(NEW_KEY);
        assertThat(keys.get(later.getId())).endsWith("|legacy|" + later.getId());
    }

    @Test
    void isIdempotent() {
        saveSleep(oldKey("2026-06-17T01:00:00Z", "2026-06-17T06:00:00Z"), 300, 25);
        saveSleep(oldKey("2026-06-16T22:00:00Z", "2026-06-17T05:30:00Z"), 450, 80);

        migration.run(null);
        Map<UUID, String> afterFirst = keysById();
        migration.run(null);

        assertThat(keysById()).isEqualTo(afterFirst);
    }

    @Test
    void leavesNewFormatOnlyDayUntouched() {
        HealthActivity current = saveSleep(NEW_KEY, 450, 80);

        migration.run(null);

        assertThat(keysById()).containsExactly(Map.entry(current.getId(), NEW_KEY));
    }

    @Test
    void archivesAllLegacyRowsWhenNewFormatKeyAlreadyExists() {
        HealthActivity current = saveSleep(NEW_KEY, 400, 70);
        HealthActivity legacy = saveSleep(oldKey("2026-06-16T22:00:00Z", "2026-06-17T06:00:00Z"), 480, 80);

        migration.run(null);

        Map<UUID, String> keys = keysById();
        assertThat(keys.get(current.getId())).isEqualTo(NEW_KEY);
        assertThat(keys.get(legacy.getId()))
                .isEqualTo(oldKey("2026-06-16T22:00:00Z", "2026-06-17T06:00:00Z") + "|legacy|" + legacy.getId());
    }

    private HealthActivity saveSleep(String externalKey, int minutes, int gainedXp) {
        return activityRepository.save(new HealthActivity(USER, DAY, ActivityType.SLEEP, "SLEEP",
                null, BigDecimal.ZERO, BigDecimal.ZERO, null, minutes, 90, gainedXp, externalKey,
                Instant.parse("2026-06-16T22:00:00Z"), Instant.parse("2026-06-17T06:00:00Z")));
    }

    private static String oldKey(String start, String end) {
        return String.join("|", USER, start, end, "SLEEP");
    }

    private Map<UUID, String> keysById() {
        return activityRepository.findAll().stream()
                .collect(Collectors.toMap(HealthActivity::getId, HealthActivity::getExternalKey));
    }
}
