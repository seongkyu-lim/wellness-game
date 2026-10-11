package com.wellnessgame.mysql;

import com.wellnessgame.WellnessGameApplication;
import com.wellnessgame.activity.ActivityType;
import com.wellnessgame.activity.ActivityXpCalculator;
import com.wellnessgame.activity.HealthActivity;
import com.wellnessgame.activity.HealthActivityRepository;
import com.wellnessgame.activity.HealthActivitySyncService;
import com.wellnessgame.activity.WorkoutType;
import com.wellnessgame.api.HealthActivitySyncRequest;
import com.wellnessgame.api.HealthActivitySyncRequest.ActivityPayload;
import com.wellnessgame.api.HealthActivitySyncResponse;
import com.wellnessgame.character.UserCharacter;
import com.wellnessgame.character.UserCharacterRepository;
import com.wellnessgame.support.ActivityPayloads;
import com.wellnessgame.support.MutableClock;
import com.wellnessgame.support.TestClockConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #66 실제 MySQL 8.4 에서만 확인할 수 있는 동작: Flyway 스키마와 ddl-auto=validate, 기존 DB baseline,
 * 행 잠금·갭 잠금·REQUIRES_NEW 를 쓰는 동시 동기화, 작은 연결 풀에서의 동시 요청.
 *
 * <p>{@code ./gradlew mysqlTest} 로 실행한다(기본 {@code test} 에서는 제외). Docker 가 없으면 로컬에서는 건너뛰고,
 * {@code CI} 환경변수가 있으면 조용히 건너뛰지 않도록 실패한다. 컨테이너는 그 확인 뒤 {@link #startMySql()} 에서 직접 띄운다.
 */
@Tag("mysql")
@SpringBootTest(classes = WellnessGameApplication.class, properties = {
        // 운영 설정과 같게: Flyway 가 스키마를 만들고 Hibernate 는 검증만 한다.
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=1",
        "spring.jpa.hibernate.ddl-auto=validate",
        // 테스트 공통 설정의 H2 용 타입 지정을 운영 기본값(MySQL)으로 되돌린다.
        "spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=" + SqlTypes.UUID,
        "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=" + SqlTypes.TIMESTAMP_UTC,
        // 연결 풀을 작게 두어 요청 하나가 연결을 두 개 잡으면 동시 요청에서 바로 고갈되게 한다.
        "spring.datasource.hikari.maximum-pool-size=" + MySqlIntegrationTest.POOL_SIZE,
        "spring.datasource.hikari.connection-timeout=3000"
})
@Import(TestClockConfig.class)
class MySqlIntegrationTest {
    static final int POOL_SIZE = 3;
    private static final String USER = "mysql-user";
    private static final LocalDate DAY = LocalDate.of(2026, 6, 17);

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10")
            .withDatabaseName("wellness_game")
            .withUsername("wellness")
            .withPassword("wellness")
            // docker-compose 의 운영 연결 URL 과 같은 옵션
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true")
            .withUrlParam("serverTimezone", "UTC");

    @BeforeAll
    static void startMySql() {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            String ci = System.getenv("CI");
            if (ci != null && !ci.isBlank()) {
                fail("CI 환경에서는 mysqlTest 를 건너뛰지 않는다: Docker 를 사용할 수 없음");
            }
            assumeTrue(false, "Docker 를 사용할 수 없어 MySQL 통합 테스트를 건너뜀");
        }
        MYSQL.start();
    }

    @AfterAll
    static void stopMySql() {
        MYSQL.stop();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @Autowired
    private HealthActivitySyncService syncService;

    @Autowired
    private HealthActivityRepository activityRepository;

    @Autowired
    private UserCharacterRepository characterRepository;

    @Autowired
    private MutableClock clock;

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        activityRepository.deleteAll();
        characterRepository.deleteAll();
        clock.setInstant(Instant.parse("2026-06-17T23:00:00Z"));
    }

    // ---- 스키마 ----

    @Test
    void flywayCreatesSchemaThatHibernateValidates() throws SQLException {
        // 컨텍스트가 떴다는 것 자체가 ddl-auto=validate 통과다.
        assertThat(flyway.info().applied()).extracting(info -> info.getVersion().getVersion(), MigrationInfo::getState)
                .containsExactly(
                        tuple("1", MigrationState.SUCCESS),
                        tuple("2", MigrationState.SUCCESS));
        assertThat(indexNames("health_activity_log")).contains(
                "uk_health_activity_external_key", "idx_health_activity_user_type_time", "idx_health_activity_user_date");
        assertThat(indexNames("user_character")).contains("uk_character_user");
        assertThat(indexNames("user_credential")).contains("uk_credential_username", "uk_credential_user_identifier");
        assertThat(((HikariDataSource) dataSource).getMaximumPoolSize()).isEqualTo(POOL_SIZE);
    }

    @Test
    void existingDdlAutoDatabaseIsBaselinedAndPassesValidateWithV1EquivalentSchema() throws Exception {
        // Flyway 이전 방식 그대로 Hibernate ddl-auto=create 로 스키마를 만든다(V1 SQL 을 쓰지 않아 V1 을 독립적으로 검증).
        String url = MYSQL.getJdbcUrl().replace("/wellness_game", "/legacy_db");
        try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = root.createStatement()) {
            statement.execute("create database legacy_db");
        }
        startApplication(url, "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create").close();

        // 이력 테이블 없음 + 구 형식 수면 로그
        UUID nap = UUID.randomUUID();
        UUID night = UUID.randomUUID();
        try (Connection legacy = DriverManager.getConnection(url, "root", MYSQL.getPassword())) {
            insertSleep(legacy, nap, USER + "|2026-06-17T01:00:00Z|2026-06-17T06:00:00Z|SLEEP", 300, 25);
            insertSleep(legacy, night, USER + "|2026-06-16T22:00:00Z|2026-06-17T05:30:00Z|SLEEP", 450, 80);
        }

        // 운영 설정 그대로 기동: baseline(1) → V2 → ddl-auto=validate. 기동에 실패하면 여기서 예외가 난다.
        startApplication(url, "spring.flyway.baseline-on-migrate=true", "spring.flyway.baseline-version=1",
                "spring.jpa.hibernate.ddl-auto=validate").close();

        Flyway legacyFlyway = Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).load();
        assertThat(legacyFlyway.info().applied()).extracting(MigrationInfo::getState)
                .containsExactly(MigrationState.BASELINE, MigrationState.SUCCESS);
        try (Connection legacy = DriverManager.getConnection(url, "root", MYSQL.getPassword());
             PreparedStatement query = legacy.prepareStatement(
                     "select external_key, gained_xp from health_activity_log where id = ?")) {
            assertThat(keyAndXp(query, night)).isEqualTo(USER + "|2026-06-17|SLEEP:105");
            assertThat(keyAndXp(query, nap)).isEqualTo(USER + "|2026-06-17T01:00:00Z|2026-06-17T06:00:00Z|SLEEP|legacy|" + nap + ":0");
        }

        // V1(빈 DB 에서 Flyway 로 만든 wellness_game)과 Hibernate 가 만든 legacy_db 의 컬럼·인덱스가 같아야 한다.
        assertThat(schemaShape("legacy_db")).isEqualTo(schemaShape("wellness_game"));
    }

    @Test
    void sleepKeyMigrationReadsIdsTheWayHibernateStoresThem() {
        HealthActivity legacy = activityRepository.save(new HealthActivity(USER, DAY, ActivityType.SLEEP, "SLEEP",
                null, BigDecimal.ZERO, BigDecimal.ZERO, null, 300, 90, 25,
                USER + "|2026-06-17T01:00:00Z|2026-06-17T06:00:00Z|SLEEP",
                Instant.parse("2026-06-17T01:00:00Z"), Instant.parse("2026-06-17T06:00:00Z")));
        activityRepository.save(new HealthActivity(USER, DAY, ActivityType.SLEEP, "SLEEP",
                null, BigDecimal.ZERO, BigDecimal.ZERO, null, 450, 90, 80, HealthActivity.sleepKey(USER, DAY),
                Instant.parse("2026-06-16T22:00:00Z"), Instant.parse("2026-06-17T05:30:00Z")));

        // V2 를 다시 적용하도록 이력에서 지우고 재실행(마이그레이션은 멱등)
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("delete from flyway_schema_history where version = '2'");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        flyway.migrate();

        HealthActivity archived = activityRepository.findById(legacy.getId()).orElseThrow();
        assertThat(archived.getExternalKey()).endsWith("|legacy|" + legacy.getId());
        assertThat(archived.getGainedXp()).isZero();
        assertThat(activityRepository.findByExternalKey(HealthActivity.sleepKey(USER, DAY)).orElseThrow().getGainedXp())
                .isEqualTo(105);
    }

    // ---- 동시 동기화 ----

    @Test
    void concurrentFirstSyncsForNewUserBothSucceedWithSingleCharacter() throws Exception {
        String newUser = "brand-new-mysql-user";
        List<HealthActivitySyncResponse> responses = runConcurrently(
                () -> sync(newUser, workoutAt(WorkoutType.RUNNING, 30, "08:00", "08:30")),
                () -> sync(newUser, workoutAt(WorkoutType.CYCLING, 30, "10:00", "10:30")));

        assertThat(responses).extracting(HealthActivitySyncResponse::gainedXp).containsExactly(105, 105);
        assertThat(characterRepository.findAll()).filteredOn(c -> c.getUserId().equals(newUser)).hasSize(1);
        assertThat(characterRepository.findByUserId(newUser).orElseThrow().getTotalXp()).isEqualTo(210);
    }

    @Test
    void concurrentSyncsForSameUserDoNotExceedDailyCap() throws Exception {
        // 120 + 운동 200 + 걸음 100 + 수면 80 = 500 → 남은 총 상한 100 (운동 상한은 200 남음)
        seedPriorXp(120);
        sync(USER,
                workoutAt(WorkoutType.RUNNING, 70, "06:00", "07:10"),
                ActivityPayloads.steps(16_000),
                ActivityPayloads.sleep(450, 90, "2026-06-16T22:00:00Z", "2026-06-17T05:30:00Z"));

        List<HealthActivitySyncResponse> responses = runConcurrently(
                () -> sync(USER, workoutAt(WorkoutType.CYCLING, 70, "10:00", "11:10")),
                () -> sync(USER, workoutAt(WorkoutType.STRENGTH_TRAINING, 70, "12:00", "13:10")));

        assertThat(responses.stream().mapToInt(HealthActivitySyncResponse::gainedXp).sum()).isEqualTo(100);
        assertThat(activityRepository.sumGainedXpByUserIdAndActivityDate(USER, DAY))
                .isEqualTo(ActivityXpCalculator.DAILY_XP_CAP);
        assertThat(characterRepository.findByUserId(USER).orElseThrow().getTotalXp())
                .isEqualTo(ActivityXpCalculator.DAILY_XP_CAP);
    }

    @Test
    void concurrentWorkoutSyncsForSameUserDoNotExceedWorkoutCap() throws Exception {
        sync(USER, workoutAt(WorkoutType.RUNNING, 70, "06:00", "07:10")); // 운동 200 → 운동 상한 200 남음

        List<HealthActivitySyncResponse> responses = runConcurrently(
                () -> sync(USER, workoutAt(WorkoutType.SWIMMING, 70, "08:00", "09:10"),
                        workoutAt(WorkoutType.WALKING, 30, "12:00", "12:30")),
                () -> sync(USER, workoutAt(WorkoutType.CYCLING, 70, "10:00", "11:10"),
                        workoutAt(WorkoutType.STRENGTH_TRAINING, 30, "14:00", "14:30")));

        assertThat(responses.stream().mapToInt(HealthActivitySyncResponse::gainedXp).sum()).isEqualTo(200);
        assertThat(activityRepository.sumGainedXpByUserIdAndActivityDateAndType(USER, DAY, ActivityType.WORKOUT))
                .isEqualTo(ActivityXpCalculator.DAILY_WORKOUT_XP_CAP);
        assertThat(characterRepository.findByUserId(USER).orElseThrow().getTotalXp())
                .isEqualTo(ActivityXpCalculator.DAILY_WORKOUT_XP_CAP);
    }

    @Test
    void manyConcurrentSyncsDoNotExhaustSmallConnectionPool() throws Exception {
        // 요청 수가 풀 크기의 몇 배여도, 요청 하나가 연결을 하나씩만 잡으면 모두 끝난다.
        // (캐릭터 생성 트랜잭션을 동기화 트랜잭션 안에 중첩하면 연결을 두 개씩 잡아 고갈·타임아웃이 난다.)
        int requests = POOL_SIZE * 4;
        List<Callable<HealthActivitySyncResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            String user = "pool-user-" + (i % (requests / 2)); // 신규 사용자 경합과 같은 사용자 직렬화를 함께
            String hour = String.format("%02d", 6 + i / (requests / 2) * 2);
            tasks.add(() -> sync(user, workoutAt(WorkoutType.RUNNING, 30, hour + ":00", hour + ":30")));
        }

        List<HealthActivitySyncResponse> responses = runConcurrently(tasks);

        assertThat(responses).hasSize(requests).allSatisfy(r -> assertThat(r.gainedXp()).isEqualTo(105));
        assertThat(characterRepository.count()).isEqualTo(requests / 2);
    }

    // ---- helpers ----

    @SafeVarargs
    private List<HealthActivitySyncResponse> runConcurrently(Callable<HealthActivitySyncResponse>... tasks) throws Exception {
        return runConcurrently(Arrays.asList(tasks));
    }

    private List<HealthActivitySyncResponse> runConcurrently(List<Callable<HealthActivitySyncResponse>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<HealthActivitySyncResponse>> futures = new ArrayList<>();
            for (Callable<HealthActivitySyncResponse> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<HealthActivitySyncResponse> responses = new ArrayList<>();
            for (Future<HealthActivitySyncResponse> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            executor.shutdownNow();
        }
    }

    private HealthActivitySyncResponse sync(String userId, ActivityPayload... activities) {
        return syncService.sync(new HealthActivitySyncRequest(userId, DAY, List.of(activities)));
    }

    private static ActivityPayload workoutAt(WorkoutType type, Integer minutes, String start, String end) {
        return ActivityPayloads.workout(type, minutes, "2026-06-17T" + start + ":00Z", "2026-06-17T" + end + ":00Z");
    }

    private void seedPriorXp(int xp) {
        activityRepository.save(new HealthActivity(USER, DAY, ActivityType.STEPS, "STEPS",
                null, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, xp,
                "seed-prior-xp|" + USER + "|" + DAY, null, null));
        UserCharacter character = new UserCharacter(USER);
        character.addXp(xp);
        characterRepository.save(character);
    }

    private List<String> indexNames(String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "select distinct index_name from information_schema.statistics where table_schema = database() and table_name = ?")) {
            query.setString(1, table);
            try (ResultSet rs = query.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    /** 테스트 컨텍스트와 별개로 legacy_db 에 연결한 앱을 웹 서버 없이 띄운다. */
    private static ConfigurableApplicationContext startApplication(String url, String... properties) {
        List<String> all = new ArrayList<>(List.of(
                "spring.datasource.url=" + url,
                "spring.datasource.username=root",
                "spring.datasource.password=" + MYSQL.getPassword(),
                "spring.datasource.driver-class-name=" + MYSQL.getDriverClassName(),
                "spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=" + SqlTypes.UUID,
                "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=" + SqlTypes.TIMESTAMP_UTC));
        all.addAll(List.of(properties));
        return new SpringApplicationBuilder(WellnessGameApplication.class)
                .web(WebApplicationType.NONE)
                .properties(all.toArray(String[]::new))
                .run();
    }

    /** 스키마의 컬럼(이름·타입·NULL 허용)과 인덱스(이름·유일 여부·컬럼 순서). Flyway 이력 테이블은 뺀다. */
    private static List<String> schemaShape(String schema) throws SQLException {
        List<String> shape = new ArrayList<>();
        // 앱 계정은 legacy_db 를 볼 권한이 없으므로 root 로 information_schema 를 읽는다.
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
            for (String sql : List.of("""
                    select concat_ws(' ', table_name, column_name, column_type, is_nullable)
                    from information_schema.columns
                    where table_schema = ? and table_name <> 'flyway_schema_history'
                    order by table_name, column_name
                    """, """
                    select concat_ws(' ', table_name, index_name, non_unique,
                                     group_concat(column_name order by seq_in_index))
                    from information_schema.statistics
                    where table_schema = ? and table_name <> 'flyway_schema_history'
                    group by table_name, index_name, non_unique
                    order by table_name, index_name
                    """)) {
                try (PreparedStatement query = connection.prepareStatement(sql)) {
                    query.setString(1, schema);
                    try (ResultSet rs = query.executeQuery()) {
                        while (rs.next()) {
                            shape.add(rs.getString(1));
                        }
                    }
                }
            }
        }
        return shape;
    }

    private static void insertSleep(Connection connection, UUID id, String externalKey, int minutes, int gainedXp)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into health_activity_log
                    (id, user_id, activity_date, type, source_type, sleep_minutes, gained_xp, external_key, created_at)
                values (?, ?, '2026-06-17', 'SLEEP', 'SLEEP', ?, ?, ?, now(6))
                """)) {
            insert.setBytes(1, bytes(id));
            insert.setString(2, USER);
            insert.setInt(3, minutes);
            insert.setInt(4, gainedXp);
            insert.setString(5, externalKey);
            insert.executeUpdate();
        }
    }

    private static String keyAndXp(PreparedStatement query, UUID id) throws SQLException {
        query.setBytes(1, bytes(id));
        try (ResultSet rs = query.executeQuery()) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1) + ":" + rs.getInt(2);
        }
    }

    /** Hibernate 가 UUID 를 binary(16) 에 저장하는 바이트 순서. */
    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
