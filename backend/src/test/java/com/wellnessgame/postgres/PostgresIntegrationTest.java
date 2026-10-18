package com.wellnessgame.postgres;

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
import com.wellnessgame.auth.IssuedRefreshToken;
import com.wellnessgame.auth.RefreshToken;
import com.wellnessgame.auth.RefreshTokenRepository;
import com.wellnessgame.auth.RefreshTokenService;
import com.wellnessgame.auth.TokenValidityService;
import com.wellnessgame.auth.UserAuthStateRepository;
import com.wellnessgame.character.UserCharacter;
import com.wellnessgame.character.UserCharacterRepository;
import com.wellnessgame.support.ActivityPayloads;
import com.wellnessgame.support.MutableClock;
import com.wellnessgame.support.TestClockConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * #87 실제 PostgreSQL 17 에서만 확인할 수 있는 동작: 빈 DB 에 Flyway 로 만든 스키마와 ddl-auto=validate, Hibernate 기본 매핑과의
 * 일치, 행 잠금(FOR UPDATE)·REQUIRES_NEW(제약 위반 뒤 aborted 트랜잭션)를 쓰는 동시 동기화, 작은 연결 풀에서의 동시 요청,
 * 리프레시 토큰 동시 회전.
 *
 * <p>{@code ./gradlew postgresTest} 로 실행한다(기본 {@code test} 에서는 제외). 서버 준비·건너뛰기 규칙은 {@link PostgresTestServer} 참고.
 * 테스트마다 새 데이터베이스를 만들고 그 DB 에 붙은 앱 컨텍스트를 직접 띄우므로(공유 컨텍스트 없음),
 * 스키마를 만들거나 이력을 고치는 테스트가 있어도 실행 순서와 무관하다. 순서 의존을 드러내도록 무작위 순서로 돈다.
 */
@Tag("postgres")
@TestMethodOrder(MethodOrderer.Random.class)
class PostgresIntegrationTest {
    static final int POOL_SIZE = 3;
    private static final String USER = "postgres-user";
    private static final LocalDate DAY = LocalDate.of(2026, 6, 17);

    private final List<String> databases = new ArrayList<>();
    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();

    private String database;
    private HealthActivitySyncService syncService;
    private HealthActivityRepository activityRepository;
    private UserCharacterRepository characterRepository;
    private RefreshTokenService refreshTokenService;
    private RefreshTokenRepository refreshTokenRepository;
    private TokenValidityService tokenValidityService;
    private UserAuthStateRepository userAuthStateRepository;
    private Flyway flyway;
    private DataSource dataSource;

    @BeforeAll
    static void startPostgres() {
        PostgresTestServer.start();
    }

    @AfterAll
    static void stopPostgres() {
        PostgresTestServer.stop();
    }

    @BeforeEach
    void setUp() {
        // 운영 설정과 같게: 빈 DB 에 Flyway 가 스키마를 만들고 Hibernate 는 검증만 한다.
        database = newDatabase();
        ConfigurableApplicationContext app = startApplication(database,
                "spring.jpa.hibernate.ddl-auto=validate",
                // 연결 풀을 작게 두어 요청 하나가 연결을 두 개 잡으면 동시 요청에서 바로 고갈되게 한다.
                "spring.datasource.hikari.maximum-pool-size=" + POOL_SIZE,
                "spring.datasource.hikari.connection-timeout=3000");
        syncService = app.getBean(HealthActivitySyncService.class);
        activityRepository = app.getBean(HealthActivityRepository.class);
        characterRepository = app.getBean(UserCharacterRepository.class);
        refreshTokenService = app.getBean(RefreshTokenService.class);
        refreshTokenRepository = app.getBean(RefreshTokenRepository.class);
        tokenValidityService = app.getBean(TokenValidityService.class);
        userAuthStateRepository = app.getBean(UserAuthStateRepository.class);
        flyway = app.getBean(Flyway.class);
        dataSource = app.getBean(DataSource.class);
        app.getBean(MutableClock.class).setInstant(Instant.parse("2026-06-17T23:00:00Z"));
    }

    @AfterEach
    void tearDown() {
        contexts.forEach(ConfigurableApplicationContext::close);
        databases.forEach(PostgresTestServer::dropDatabase);
    }

    // ---- 스키마 ----

    @Test
    void flywayCreatesSchemaThatHibernateValidates() throws SQLException {
        // 컨텍스트가 떴다는 것 자체가 ddl-auto=validate 통과다. db/migration 의 모든 마이그레이션이 적용돼 있어야 한다.
        assertThat(flyway.info().applied()).extracting(info -> info.getVersion().getVersion(), MigrationInfo::getState)
                .containsExactlyElementsOf(resolvedVersions().stream()
                        .map(version -> tuple(version, MigrationState.SUCCESS)).toList());
        assertThat(indexNames("health_activity_log")).contains(
                "uk_health_activity_external_key", "idx_health_activity_user_type_time", "idx_health_activity_user_date");
        assertThat(indexNames("user_character")).contains("uk_character_user");
        assertThat(indexNames("user_credential")).contains("uk_credential_username", "uk_credential_user_identifier");
        assertThat(indexNames("refresh_token")).contains("uk_refresh_token_hash", "idx_refresh_token_family",
                "idx_refresh_token_user", "idx_refresh_token_expires");
        assertThat(columnType("health_activity_log", "id")).isEqualTo("uuid");
        assertThat(columnType("health_activity_log", "created_at")).isEqualTo("timestamp with time zone");
        assertThat(columnType("refresh_token", "family_id")).isEqualTo("uuid");
        assertThat(((HikariDataSource) dataSource).getMaximumPoolSize()).isEqualTo(POOL_SIZE);
    }

    @Test
    void migrationsMatchHibernatePostgresMappingAndExistingTablesWithoutHistoryFailFast() throws Exception {
        // 마이그레이션을 쓰지 않고 Hibernate 가 지금 엔티티로 만든 PostgreSQL 스키마(기본 타입 매핑)와 비교한다.
        String hibernateDb = newDatabase();
        startApplication(hibernateDb, "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create").close();

        assertThat(schemaShape(database)).isEqualTo(schemaShape(hibernateDb));

        // 테이블은 있는데 Flyway 이력이 없는 DB 는 기준선을 잡지 않고 기동에 실패한다(baseline-on-migrate 를 쓰지 않음).
        assertThatThrownBy(() -> startApplication(hibernateDb, "spring.jpa.hibernate.ddl-auto=validate"))
                .hasStackTraceContaining("non-empty schema");
    }

    @Test
    void instantsAreStoredAsUtcTimestamptz() throws SQLException {
        Instant startedAt = Instant.parse("2026-06-17T01:00:00.123456Z");
        HealthActivity saved = activityRepository.save(new HealthActivity(USER, DAY, ActivityType.WORKOUT, "WORKOUT",
                30, new BigDecimal("123.45"), new BigDecimal("5000.50"), null, null, null, 105, "instant|" + USER,
                startedAt, startedAt.plusSeconds(1800)));

        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     select to_char(started_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US'), calories
                     from health_activity_log where id = ?
                     """)) {
            query.setObject(1, saved.getId());
            try (ResultSet rs = query.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("2026-06-17T01:00:00.123456");
                assertThat(rs.getBigDecimal(2)).isEqualByComparingTo("123.45");
            }
        }
        assertThat(activityRepository.findById(saved.getId()).orElseThrow().getStartedAt()).isEqualTo(startedAt);
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

        rerunSleepKeyMigration();

        HealthActivity archived = activityRepository.findById(legacy.getId()).orElseThrow();
        assertThat(archived.getExternalKey()).endsWith("|legacy|" + legacy.getId());
        assertThat(archived.getGainedXp()).isZero();
        assertThat(activityRepository.findByExternalKey(HealthActivity.sleepKey(USER, DAY)).orElseThrow().getGainedXp())
                .isEqualTo(105);
    }

    @Test
    void sleepKeyMigrationPromotesAndArchivesRowsInsertedWithJdbc() throws SQLException {
        UUID nap = UUID.randomUUID();
        UUID night = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            insertSleep(connection, nap, USER + "|2026-06-17T01:00:00Z|2026-06-17T06:00:00Z|SLEEP", 300, 25);
            insertSleep(connection, night, USER + "|2026-06-16T22:00:00Z|2026-06-17T05:30:00Z|SLEEP", 450, 80);
        }

        rerunSleepKeyMigration();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "select external_key, gained_xp from health_activity_log where id = ?")) {
            assertThat(keyAndXp(query, night)).isEqualTo(USER + "|2026-06-17|SLEEP:105");
            assertThat(keyAndXp(query, nap)).isEqualTo(USER + "|2026-06-17T01:00:00Z|2026-06-17T06:00:00Z|SLEEP|legacy|" + nap + ":0");
        }
    }

    // ---- 동시 동기화 ----

    @Test
    void concurrentFirstSyncsForNewUserBothSucceedWithSingleCharacter() throws Exception {
        String newUser = "brand-new-postgres-user";
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

    // ---- 리프레시 토큰 ----

    @Test
    void concurrentRefreshWithSameTokenIsSerializedByRowLock() throws Exception {
        IssuedRefreshToken first = refreshTokenService.issue(USER);
        List<RefreshTokenService.Rotation> rotations = runConcurrently(List.of(
                () -> refreshTokenService.rotate(first.refreshToken()),
                () -> refreshTokenService.rotate(first.refreshToken())));

        String tokenA = rotations.get(0).refreshToken().refreshToken();
        String tokenB = rotations.get(1).refreshToken().refreshToken();
        assertThat(tokenA).isNotEqualTo(tokenB);

        // 행 잠금으로 직렬화되므로 원본은 한 번만 회전(replaced_by 하나)되고, 뒤 요청은 유예 구간의 형제 토큰을 받는다.
        List<RefreshToken> family = refreshTokenRepository.findByUserId(USER);
        assertThat(family).hasSize(3).extracting(RefreshToken::getFamilyId).containsOnly(family.get(0).getFamilyId());
        assertThat(family).filteredOn(token -> token.getRevokedAt() != null).singleElement()
                .satisfies(original -> assertThat(original.getReplacedBy()).isNotNull());

        // 두 새 토큰 모두 계속 회전할 수 있다.
        assertThat(refreshTokenService.rotate(tokenA).userId()).isEqualTo(USER);
        assertThat(refreshTokenService.rotate(tokenB).userId()).isEqualTo(USER);
    }

    @Test
    void concurrentFirstRevokesForSameUserAllSucceedWithSingleRow() throws Exception {
        // 첫 revoke 가 동시에 와도 PK 위반(23505)으로 실패하거나 트랜잭션이 aborted 되지 않아야 한다(네이티브 upsert).
        String user = "revoke-race-user";
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < POOL_SIZE * 2; i++) {
            tasks.add(() -> {
                tokenValidityService.revokeIssuedTokens(user);
                return true;
            });
        }

        assertThat(runConcurrently(tasks)).hasSize(POOL_SIZE * 2).containsOnly(true);
        assertThat(userAuthStateRepository.findAll()).filteredOn(state -> state.getUserId().equals(user)).singleElement()
                .satisfies(state -> assertThat(state.getTokensValidAfter()).isEqualTo(Instant.parse("2026-06-17T23:00:00Z")));
    }

    // ---- helpers ----

    @SafeVarargs
    private List<HealthActivitySyncResponse> runConcurrently(Callable<HealthActivitySyncResponse>... tasks) throws Exception {
        return runConcurrently(Arrays.asList(tasks));
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
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

    /**
     * V2 를 다시 적용하도록 이력에서 지우고 재실행한다(마이그레이션은 멱등). V2 뒤 버전이 이미 적용돼 있으므로
     * 이 테스트 DB 에서만 outOfOrder 로 V2 를 다시 돌린다.
     */
    private void rerunSleepKeyMigration() {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("delete from flyway_schema_history where version = '2'");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        Flyway.configure().configuration(flyway.getConfiguration()).outOfOrder(true).load().migrate();
    }

    private List<String> indexNames(String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "select indexname from pg_indexes where schemaname = current_schema() and tablename = ?")) {
            query.setString(1, table);
            try (ResultSet rs = query.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    private String columnType(String table, String column) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     select data_type from information_schema.columns
                     where table_schema = current_schema() and table_name = ? and column_name = ?
                     """)) {
            query.setString(1, table);
            query.setString(2, column);
            try (ResultSet rs = query.executeQuery()) {
                assertThat(rs.next()).as("%s.%s", table, column).isTrue();
                return rs.getString(1);
            }
        }
    }

    private String newDatabase() {
        String name = PostgresTestServer.createDatabase();
        databases.add(name);
        return name;
    }

    /**
     * classpath:db/migration 에서 해석된 모든 마이그레이션 버전(오름차순, 적용 안 된 것 포함). 새 마이그레이션이 추가돼도
     * 그대로 맞는다. Flyway 정보 조회에는 DB 연결이 필요해 테스트 DB 에 붙은 Flyway 로 읽는다.
     */
    private List<String> resolvedVersions() {
        assertThat(flyway.getConfiguration().getLocations()).extracting(Object::toString)
                .containsExactly("classpath:db/migration");
        return Arrays.stream(flyway.info().all())
                .map(info -> info.getVersion().getVersion())
                .toList();
    }

    /** 주어진 테스트 DB 에 붙은 앱을 웹 서버 없이 띄운다. 테스트가 끝나면 {@link #tearDown()} 이 닫는다. */
    private ConfigurableApplicationContext startApplication(String db, String... properties) {
        List<String> all = new ArrayList<>(List.of(
                "spring.datasource.url=" + PostgresTestServer.jdbcUrl(db),
                "spring.datasource.username=" + PostgresTestServer.username(),
                "spring.datasource.password=" + PostgresTestServer.password(),
                "spring.datasource.driver-class-name=org.postgresql.Driver"));
        all.addAll(List.of(properties));
        ConfigurableApplicationContext context = new SpringApplicationBuilder(WellnessGameApplication.class, TestClockConfig.class)
                .web(WebApplicationType.NONE)
                // properties() 는 기본값(가장 낮은 우선순위)이라 테스트 application.yml 의 H2 설정에 가려진다.
                // 명령행 인자로 넘겨 반드시 이 DB 에 붙게 한다.
                .run(all.stream().map(property -> "--" + property).toArray(String[]::new));
        contexts.add(context);
        return context;
    }

    /**
     * 스키마의 컬럼(이름·타입·길이·정밀도·NULL 허용)과 인덱스(이름·정의). 제약 이름이 그대로 인덱스 이름이 되는 기본 키·유일 제약도
     * 함께 비교된다. Flyway 이력 테이블은 뺀다.
     */
    private static List<String> schemaShape(String db) throws SQLException {
        List<String> shape = new ArrayList<>();
        try (Connection connection = PostgresTestServer.connect(db)) {
            for (String sql : List.of("""
                    select concat_ws(' ', table_name, column_name, data_type, character_maximum_length,
                                     numeric_precision, numeric_scale, datetime_precision, is_nullable)
                    from information_schema.columns
                    where table_schema = current_schema() and table_name <> 'flyway_schema_history'
                    order by table_name, column_name
                    """, """
                    select concat_ws(' ', tablename, indexname, indexdef)
                    from pg_indexes
                    where schemaname = current_schema() and tablename <> 'flyway_schema_history'
                    order by tablename, indexname
                    """)) {
                try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                    while (rs.next()) {
                        shape.add(rs.getString(1));
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
                values (?, ?, date '2026-06-17', 'SLEEP', 'SLEEP', ?, ?, ?, now())
                """)) {
            insert.setObject(1, id);
            insert.setString(2, USER);
            insert.setInt(3, minutes);
            insert.setInt(4, gainedXp);
            insert.setString(5, externalKey);
            insert.executeUpdate();
        }
    }

    private static String keyAndXp(PreparedStatement query, UUID id) throws SQLException {
        query.setObject(1, id);
        try (ResultSet rs = query.executeQuery()) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1) + ":" + rs.getInt(2);
        }
    }
}
