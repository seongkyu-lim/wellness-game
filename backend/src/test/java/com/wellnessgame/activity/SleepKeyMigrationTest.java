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
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.wellnessgame.support.ActivityPayloads.sleep;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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

    @Autowired
    private PlatformTransactionManager transactionManager;

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

    @Test
    void migratesEveryUserAndDateGroupIndependently() {
        HealthActivity aDay1 = saveSleep("user-a", DAY, oldKeyOf("user-a", "2026-06-16T22:00:00Z", "2026-06-17T06:00:00Z"), 480, 80);
        HealthActivity aDay2 = saveSleep("user-a", DAY.plusDays(1), oldKeyOf("user-a", "2026-06-17T22:00:00Z", "2026-06-18T05:00:00Z"), 420, 60);
        HealthActivity bDay1 = saveSleep("user-b", DAY, oldKeyOf("user-b", "2026-06-16T23:00:00Z", "2026-06-17T06:00:00Z"), 420, 60);

        migration.run(null);

        Map<UUID, String> keys = keysById();
        assertThat(keys.get(aDay1.getId())).isEqualTo(HealthActivity.sleepKey("user-a", DAY));
        assertThat(keys.get(aDay2.getId())).isEqualTo(HealthActivity.sleepKey("user-a", DAY.plusDays(1)));
        assertThat(keys.get(bDay1.getId())).isEqualTo(HealthActivity.sleepKey("user-b", DAY));
    }

    @Test
    void uniqueConflictInOneGroupArchivesItAndOtherGroupsStillMigrate() {
        // user-a: 확인 시점엔 새 키가 없다고 보였지만 실제로는 있다(그사이 생긴 경우) → 유일 제약 충돌
        HealthActivity aCurrent = saveSleep("user-a", DAY, HealthActivity.sleepKey("user-a", DAY), 400, 70);
        HealthActivity aLegacy = saveSleep("user-a", DAY, oldKeyOf("user-a", "2026-06-16T22:00:00Z", "2026-06-17T06:00:00Z"), 480, 80);
        HealthActivity bLegacy = saveSleep("user-b", DAY, oldKeyOf("user-b", "2026-06-16T23:00:00Z", "2026-06-17T06:00:00Z"), 420, 60);
        HealthActivityRepository racing = existsAlwaysFalse(activityRepository);

        assertThatCode(() -> new SleepKeyMigration(racing, transactionManager).run(null)).doesNotThrowAnyException();

        Map<UUID, String> keys = keysById();
        assertThat(keys.get(aCurrent.getId())).isEqualTo(HealthActivity.sleepKey("user-a", DAY));
        assertThat(keys.get(aLegacy.getId())).endsWith("|legacy|" + aLegacy.getId());
        assertThat(keys.get(bLegacy.getId())).isEqualTo(HealthActivity.sleepKey("user-b", DAY));
        assertThat(activityRepository.count()).isEqualTo(3);
    }

    @Test
    void failureToLoadCandidatesDoesNotBreakStartup() {
        HealthActivityRepository broken = (HealthActivityRepository) Proxy.newProxyInstance(
                HealthActivityRepository.class.getClassLoader(), new Class<?>[]{HealthActivityRepository.class},
                (proxy, method, args) -> {
                    throw new IllegalStateException("db down");
                });

        assertThatCode(() -> new SleepKeyMigration(broken, transactionManager).run(null)).doesNotThrowAnyException();
    }

    /** existsByExternalKey 만 항상 false 로 답하고 나머지는 실제 저장소에 위임한다(확인 후 새 키가 생긴 경합 재현). */
    private static HealthActivityRepository existsAlwaysFalse(HealthActivityRepository delegate) {
        return (HealthActivityRepository) Proxy.newProxyInstance(
                HealthActivityRepository.class.getClassLoader(), new Class<?>[]{HealthActivityRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("existsByExternalKey")) {
                        return false;
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private HealthActivity saveSleep(String externalKey, int minutes, int gainedXp) {
        return saveSleep(USER, DAY, externalKey, minutes, gainedXp);
    }

    private HealthActivity saveSleep(String userId, LocalDate date, String externalKey, int minutes, int gainedXp) {
        return activityRepository.save(new HealthActivity(userId, date, ActivityType.SLEEP, "SLEEP",
                null, BigDecimal.ZERO, BigDecimal.ZERO, null, minutes, 90, gainedXp, externalKey,
                Instant.parse("2026-06-16T22:00:00Z"), Instant.parse("2026-06-17T06:00:00Z")));
    }

    private static String oldKey(String start, String end) {
        return oldKeyOf(USER, start, end);
    }

    private static String oldKeyOf(String userId, String start, String end) {
        return String.join("|", userId, start, end, "SLEEP");
    }

    private Map<UUID, String> keysById() {
        return activityRepository.findAll().stream()
                .collect(Collectors.toMap(HealthActivity::getId, HealthActivity::getExternalKey));
    }
}
