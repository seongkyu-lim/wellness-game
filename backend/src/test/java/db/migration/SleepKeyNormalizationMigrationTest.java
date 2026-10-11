package db.migration;

import com.wellnessgame.WellnessGameApplication;
import com.wellnessgame.activity.ActivityType;
import com.wellnessgame.activity.HealthActivity;
import com.wellnessgame.activity.HealthActivityRepository;
import com.wellnessgame.activity.HealthActivitySyncService;
import com.wellnessgame.api.HealthActivitySyncRequest;
import com.wellnessgame.api.HealthActivitySyncResponse;
import com.wellnessgame.character.UserCharacterRepository;
import com.wellnessgame.support.MutableClock;
import com.wellnessgame.support.TestClockConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.wellnessgame.support.ActivityPayloads.sleep;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #50 구 형식 수면 로그 키(userId|start|end|SLEEP) 정리와 #66 legacy XP 합치기(Flyway V2).
 * 앱 기동 때 Flyway 가 이미 V2 를 빈 DB 에 적용했으므로, 여기서는 데이터를 넣은 뒤 같은 마이그레이션을 직접 다시 실행한다.
 */
@SpringBootTest(classes = WellnessGameApplication.class)
@Import(TestClockConfig.class)
class SleepKeyNormalizationMigrationTest {
    private static final String USER = "sleep-migration-user";
    private static final LocalDate DAY = LocalDate.of(2026, 6, 17);
    private static final String NEW_KEY = USER + "|" + DAY + "|SLEEP";

    @Autowired
    private HealthActivitySyncService syncService;

    @Autowired
    private HealthActivityRepository activityRepository;

    @Autowired
    private UserCharacterRepository characterRepository;

    @Autowired
    private MutableClock clock;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @BeforeEach
    void setUp() {
        activityRepository.deleteAll();
        characterRepository.deleteAll();
        clock.setInstant(Instant.parse("2026-06-17T23:00:00Z"));
    }

    @Test
    void flywayAppliedBaselineAndSleepKeyMigrationAtStartup() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void promotesLongestLegacySleepAndArchivesTheRestSoResyncGrantsNothing() throws SQLException {
        HealthActivity shortNap = saveSleep(oldKey("2026-06-17T01:00:00Z", "2026-06-17T06:00:00Z"), 300, 25);
        HealthActivity night = saveSleep(oldKey("2026-06-16T22:00:00Z", "2026-06-17T05:30:00Z"), 450, 80);

        migrate();

        Map<UUID, String> keys = keysById();
        assertThat(keys.get(night.getId())).isEqualTo(NEW_KEY);
        assertThat(keys.get(shortNap.getId()))
                .isEqualTo(oldKey("2026-06-17T01:00:00Z", "2026-06-17T06:00:00Z") + "|legacy|" + shortNap.getId());
        assertThat(activityRepository.count()).isEqualTo(2); // 삭제 없음
        assertThat(xpById()).containsEntry(night.getId(), 105).containsEntry(shortNap.getId(), 0);
        assertThat(activityRepository.sumGainedXpByUserIdAndActivityDate(USER, DAY)).isEqualTo(105);

        HealthActivitySyncResponse resync = syncService.sync(new HealthActivitySyncRequest(USER, DAY,
                List.of(sleep(450, 90, "2026-06-16T22:00:00Z", "2026-06-17T05:30:00Z"))));

        assertThat(resync.gainedXp()).isZero();
        assertThat(resync.activityResults().get(0).duplicate()).isTrue();
        assertThat(activityRepository.count()).isEqualTo(2);
        assertThat(activityRepository.sumGainedXpByUserIdAndActivityDate(USER, DAY)).isEqualTo(105);
    }

    @Test
    void longerSleepAfterMigrationPaysOnlyBeyondWhatLegacyRowsAlreadyPaid() throws SQLException {
        // 승자 40 + legacy 25 = 65 를 이미 지급했다. 이후 80 XP 짜리 수면이 오면 차액 15 만 지급해야 한다
        // (승자 행 40 만 기준으로 삼으면 40 을 더 줘 그날 수면 XP 가 105 가 된다).
        saveSleep(oldKey("2026-06-17T01:00:00Z", "2026-06-17T06:00:00Z"), 300, 25);
        saveSleep(oldKey("2026-06-16T22:00:00Z", "2026-06-17T04:40:00Z"), 400, 40);

        migrate();
        HealthActivitySyncResponse longer = syncService.sync(new HealthActivitySyncRequest(USER, DAY,
                List.of(sleep(480, 90, "2026-06-16T22:00:00Z", "2026-06-17T06:00:00Z"))));
        HealthActivitySyncResponse again = syncService.sync(new HealthActivitySyncRequest(USER, DAY,
                List.of(sleep(480, 90, "2026-06-16T22:00:00Z", "2026-06-17T06:00:00Z"))));

        assertThat(longer.gainedXp()).isEqualTo(15);
        assertThat(again.gainedXp()).isZero();
        assertThat(activityRepository.sumGainedXpByUserIdAndActivityDate(USER, DAY)).isEqualTo(80);
        assertThat(activityRepository.findByExternalKey(NEW_KEY)).get()
                .satisfies(log -> assertThat(log.getGainedXp()).isEqualTo(80));
    }

    @Test
    void tieOnSleepMinutesPromotesEarliestCreated() throws Exception {
        HealthActivity earlier = saveSleep(oldKey("2026-06-16T22:00:00Z", "2026-06-17T05:00:00Z"), 420, 60);
        Thread.sleep(5);
        HealthActivity later = saveSleep(oldKey("2026-06-16T23:00:00Z", "2026-06-17T06:00:00Z"), 420, 60);

        migrate();

        Map<UUID, String> keys = keysById();
        assertThat(keys.get(earlier.getId())).isEqualTo(NEW_KEY);
        assertThat(keys.get(later.getId())).endsWith("|legacy|" + later.getId());
    }

    @Test
    void isIdempotent() throws SQLException {
        saveSleep(oldKey("2026-06-17T01:00:00Z", "2026-06-17T06:00:00Z"), 300, 25);
        saveSleep(oldKey("2026-06-16T22:00:00Z", "2026-06-17T05:30:00Z"), 450, 80);

        migrate();
        Map<UUID, String> keysAfterFirst = keysById();
        Map<UUID, Integer> xpAfterFirst = xpById();
        migrate();

        assertThat(keysById()).isEqualTo(keysAfterFirst);
        assertThat(xpById()).isEqualTo(xpAfterFirst);
    }

    @Test
    void leavesNewFormatOnlyDayUntouched() throws SQLException {
        HealthActivity current = saveSleep(NEW_KEY, 450, 80);

        migrate();

        assertThat(keysById()).containsExactly(Map.entry(current.getId(), NEW_KEY));
        assertThat(xpById()).containsExactly(Map.entry(current.getId(), 80));
    }

    @Test
    void archivesAllLegacyRowsWhenNewFormatKeyAlreadyExistsAndMovesTheirXp() throws SQLException {
        HealthActivity current = saveSleep(NEW_KEY, 400, 70);
        HealthActivity legacy = saveSleep(oldKey("2026-06-16T22:00:00Z", "2026-06-17T06:00:00Z"), 480, 80);

        migrate();

        Map<UUID, String> keys = keysById();
        assertThat(keys.get(current.getId())).isEqualTo(NEW_KEY);
        assertThat(keys.get(legacy.getId()))
                .isEqualTo(oldKey("2026-06-16T22:00:00Z", "2026-06-17T06:00:00Z") + "|legacy|" + legacy.getId());
        assertThat(xpById()).containsEntry(current.getId(), 150).containsEntry(legacy.getId(), 0);
    }

    @Test
    void movesXpOfRowsArchivedByThePreviousStartupMigration() throws SQLException {
        // Flyway 이전의 기동 시 마이그레이션이 키만 정리해 둔 DB(baseline 후 V2 실행)
        HealthActivity current = saveSleep(NEW_KEY, 450, 80);
        HealthActivity archived = saveSleep(oldKey("2026-06-17T01:00:00Z", "2026-06-17T06:00:00Z") + "|legacy|x", 300, 25);

        migrate();

        assertThat(xpById()).containsEntry(current.getId(), 105).containsEntry(archived.getId(), 0);
        assertThat(keysById().get(archived.getId())).endsWith("|legacy|x");
    }

    @Test
    void migratesEveryUserAndDateGroupIndependently() throws SQLException {
        HealthActivity aDay1 = saveSleep("user-a", DAY, oldKeyOf("user-a", "2026-06-16T22:00:00Z", "2026-06-17T06:00:00Z"), 480, 80);
        HealthActivity aDay2 = saveSleep("user-a", DAY.plusDays(1), oldKeyOf("user-a", "2026-06-17T22:00:00Z", "2026-06-18T05:00:00Z"), 420, 60);
        HealthActivity bDay1 = saveSleep("user-b", DAY, oldKeyOf("user-b", "2026-06-16T23:00:00Z", "2026-06-17T06:00:00Z"), 420, 60);

        migrate();

        Map<UUID, String> keys = keysById();
        assertThat(keys.get(aDay1.getId())).isEqualTo(HealthActivity.sleepKey("user-a", DAY));
        assertThat(keys.get(aDay2.getId())).isEqualTo(HealthActivity.sleepKey("user-a", DAY.plusDays(1)));
        assertThat(keys.get(bDay1.getId())).isEqualTo(HealthActivity.sleepKey("user-b", DAY));
    }

    @Test
    void uniqueConflictInOneGroupArchivesItAndOtherGroupsStillMigrate() throws SQLException {
        // user-a: 확인 시점엔 새 키가 없다고 보였지만 실제로는 있다(그사이 생긴 경우) → 유일 제약 충돌
        HealthActivity aCurrent = saveSleep("user-a", DAY, HealthActivity.sleepKey("user-a", DAY), 400, 70);
        HealthActivity aLegacy = saveSleep("user-a", DAY, oldKeyOf("user-a", "2026-06-16T22:00:00Z", "2026-06-17T06:00:00Z"), 480, 80);
        HealthActivity bLegacy = saveSleep("user-b", DAY, oldKeyOf("user-b", "2026-06-16T23:00:00Z", "2026-06-17T06:00:00Z"), 420, 60);

        migrate(new V2__sleep_key_normalization((connection, sleepKey) -> false));

        Map<UUID, String> keys = keysById();
        assertThat(keys.get(aCurrent.getId())).isEqualTo(HealthActivity.sleepKey("user-a", DAY));
        assertThat(keys.get(aLegacy.getId())).endsWith("|legacy|" + aLegacy.getId());
        assertThat(keys.get(bLegacy.getId())).isEqualTo(HealthActivity.sleepKey("user-b", DAY));
        assertThat(xpById()).containsEntry(aCurrent.getId(), 150).containsEntry(aLegacy.getId(), 0);
        assertThat(activityRepository.count()).isEqualTo(3);
    }

    @Test
    void otherFailuresAbortAndRollBackTheWholeMigration() {
        saveSleep("user-a", DAY, oldKeyOf("user-a", "2026-06-16T22:00:00Z", "2026-06-17T06:00:00Z"), 480, 80);
        saveSleep("user-b", DAY, oldKeyOf("user-b", "2026-06-16T23:00:00Z", "2026-06-17T06:00:00Z"), 420, 60);
        Map<UUID, String> before = keysById();

        AtomicInteger calls = new AtomicInteger();
        V2__sleep_key_normalization failingOnSecondGroup = new V2__sleep_key_normalization((connection, sleepKey) -> {
            if (calls.incrementAndGet() == 2) {
                throw new SQLException("connection lost", "08S01");
            }
            return V2__sleep_key_normalization.sleepKeyExists(connection, sleepKey);
        });

        assertThatThrownBy(() -> migrate(failingOnSecondGroup)).isInstanceOf(SQLException.class);

        assertThat(keysById()).isEqualTo(before);
    }

    /** Flyway 처럼 한 트랜잭션 안에서 실행하고, 실패하면 되돌린다. */
    private void migrate() throws SQLException {
        migrate(new V2__sleep_key_normalization());
    }

    private void migrate(V2__sleep_key_normalization migration) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                migration.migrate(connection);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
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

    private Map<UUID, Integer> xpById() {
        return activityRepository.findAll().stream()
                .collect(Collectors.toMap(HealthActivity::getId, HealthActivity::getGainedXp));
    }
}
