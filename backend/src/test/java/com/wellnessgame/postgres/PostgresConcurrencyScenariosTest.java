package com.wellnessgame.postgres;

import com.wellnessgame.WellnessGameApplication;
import com.wellnessgame.activity.HealthActivityRepository;
import com.wellnessgame.activity.HealthActivitySyncService;
import com.wellnessgame.activity.WorkoutType;
import com.wellnessgame.api.HealthActivitySyncRequest;
import com.wellnessgame.api.HealthActivitySyncRequest.ActivityPayload;
import com.wellnessgame.api.HealthActivitySyncResponse;
import com.wellnessgame.auth.RefreshToken;
import com.wellnessgame.auth.RefreshTokenRepository;
import com.wellnessgame.auth.RefreshTokenService;
import com.wellnessgame.auth.UnauthorizedException;
import com.wellnessgame.character.UserCharacterRepository;
import com.wellnessgame.support.ActivityPayloads;
import com.wellnessgame.support.MutableClock;
import com.wellnessgame.support.TestClockConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 PostgreSQL 에서의 동시성 시나리오(#152). 행 잠금(FOR UPDATE)과 트랜잭션 격리에 기대는 불변식을 확인한다.
 *
 * <ul>
 *   <li>같은 리프레시 토큰의 동시 회전: 정확히 한 번의 유예 재발급, 그 이상은 재사용으로 family 폐기.</li>
 *   <li>같은 사용자의 같은 동기화 요청 동시 전송: XP 이중 지급 없음, 상한 유지.</li>
 *   <li>로그아웃과 회전 경쟁: 어떤 순서로 끝나도 살아 있는 토큰이 남지 않음.</li>
 * </ul>
 * 전체 로그아웃(revokeAll)과 회전의 경쟁은 family 잠금 없이 UPDATE 하므로 후계 토큰이 살아남을 수 있어(확인됨) 여기서는 단언하지 않는다.
 * 시간은 주입된 MutableClock 으로 고정해 타이밍에 기대지 않는다. 경쟁 순서는 매번 다를 수 있지만 단언은 모든 순서에서 성립하는 불변식만 쓴다.
 * {@code ./gradlew postgresTest} 로 실행한다.
 */
@Tag("postgres")
class PostgresConcurrencyScenariosTest {
    private static final LocalDate DAY = LocalDate.of(2026, 6, 17);
    private static final Instant NOW = Instant.parse("2026-06-17T23:00:00Z");
    private static final int POOL_SIZE = 4;

    // 클래스당 컨텍스트·데이터베이스 하나를 공유한다. 테스트는 서로 다른 사용자 ID 를 쓰고 사용자별로 단언하므로 순서에 무관하다.
    private static String database;
    private static ConfigurableApplicationContext app;
    private static HealthActivitySyncService syncService;
    private static HealthActivityRepository activityRepository;
    private static UserCharacterRepository characterRepository;
    private static RefreshTokenService refreshTokenService;
    private static RefreshTokenRepository refreshTokenRepository;

    @BeforeAll
    static void startPostgresAndApp() {
        PostgresTestServer.start();
        database = PostgresTestServer.createDatabase();
        app = new SpringApplicationBuilder(WellnessGameApplication.class, TestClockConfig.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + PostgresTestServer.jdbcUrl(database),
                        "--spring.datasource.username=" + PostgresTestServer.username(),
                        "--spring.datasource.password=" + PostgresTestServer.password(),
                        "--spring.datasource.driver-class-name=org.postgresql.Driver",
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        "--spring.datasource.hikari.maximum-pool-size=" + POOL_SIZE,
                        "--spring.datasource.hikari.connection-timeout=5000");
        syncService = app.getBean(HealthActivitySyncService.class);
        activityRepository = app.getBean(HealthActivityRepository.class);
        characterRepository = app.getBean(UserCharacterRepository.class);
        refreshTokenService = app.getBean(RefreshTokenService.class);
        refreshTokenRepository = app.getBean(RefreshTokenRepository.class);
        app.getBean(MutableClock.class).setInstant(NOW);
    }

    @AfterAll
    static void stopApp() {
        if (app != null) {
            app.close();
        }
        if (database != null) {
            PostgresTestServer.dropDatabase(database);
        }
        PostgresTestServer.stop();
    }

    // ---- 리프레시 토큰: 동시 회전 ----

    @Test
    void twoConcurrentRotationsOfSameTokenProduceExactlyOneGraceReissue() throws Exception {
        String user = "rotate-two";
        String first = refreshTokenService.issue(user).refreshToken();

        List<Outcome<RefreshTokenService.Rotation>> outcomes = runConcurrently(List.of(
                () -> refreshTokenService.rotate(first), () -> refreshTokenService.rotate(first)));

        assertThat(outcomes).hasSize(2).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
        List<RefreshToken> family = refreshTokenRepository.findByUserId(user);
        assertThat(family).hasSize(3);
        assertThat(family.stream().filter(RefreshToken::isGraceReissued)).hasSize(1);
        assertThat(family.stream().filter(token -> token.getRevokedAt() == null)).hasSize(2);
        // 원본은 한 번만 회전됐다
        assertThat(family.stream().filter(token -> token.getReplacedBy() != null)).hasSize(1);
    }

    @Test
    void threeConcurrentRotationsOfSameTokenAllowTwoThenDetectReuse() throws Exception {
        String user = "rotate-three";
        String first = refreshTokenService.issue(user).refreshToken();

        List<Outcome<RefreshTokenService.Rotation>> outcomes = runConcurrently(List.of(
                () -> refreshTokenService.rotate(first),
                () -> refreshTokenService.rotate(first),
                () -> refreshTokenService.rotate(first)));

        // 직렬화되므로 1번째는 정상 회전, 2번째는 유예 재발급, 3번째는 두 번째 사용이라 재사용 감지
        assertThat(outcomes.stream().filter(o -> o.error() == null)).hasSize(2);
        assertThat(outcomes.stream().filter(o -> o.error() != null)).singleElement()
                .satisfies(o -> assertThat(o.error()).isInstanceOf(UnauthorizedException.class));
        // 재사용 감지 결과(family 폐기)는 예외에도 불구하고 커밋된다
        assertThat(refreshTokenRepository.findByUserId(user)).isNotEmpty().allSatisfy(token ->
                assertThat(token.getRevokedAt()).isNotNull());
        for (Outcome<RefreshTokenService.Rotation> ok : outcomes.stream().filter(o -> o.error() == null).toList()) {
            String issued = ok.value().refreshToken().refreshToken();
            assertThatThrownBy(() -> refreshTokenService.rotate(issued)).isInstanceOf(UnauthorizedException.class);
        }
    }

    @Test
    void concurrentRotationsOfDifferentFamiliesAllSucceed() throws Exception {
        List<Callable<RefreshTokenService.Rotation>> tasks = new ArrayList<>();
        for (int i = 0; i < POOL_SIZE * 2; i++) {
            String raw = refreshTokenService.issue("multi-" + i).refreshToken();
            tasks.add(() -> refreshTokenService.rotate(raw));
        }

        List<Outcome<RefreshTokenService.Rotation>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).hasSize(POOL_SIZE * 2)
                .allSatisfy(outcome -> assertThat(outcome.error()).isNull());
        for (int i = 0; i < POOL_SIZE * 2; i++) {
            assertThat(refreshTokenRepository.findByUserId("multi-" + i)).hasSize(2); // 원본 + 후계
        }
    }

    // ---- 로그아웃 vs 회전 ----

    @Test
    void logoutRacingRotationLeavesNoUsableTokenInEitherOrder() throws Exception {
        // 경쟁 순서가 매번 달라질 수 있어 여러 번 반복한다. 모든 순서에서 같은 불변식이 성립해야 한다:
        //  (a) 회전이 먼저: 후계 토큰까지 로그아웃이 폐기한다  (b) 로그아웃이 먼저: 회전은 거부된다
        for (int i = 0; i < 15; i++) {
            String user = "logout-race-" + i;
            String first = refreshTokenService.issue(user).refreshToken();

            List<Outcome<Object>> outcomes = runConcurrently(List.of(
                    () -> refreshTokenService.rotate(first),
                    () -> {
                        refreshTokenService.revokeFamily(first);
                        return "revoked";
                    }));

            Outcome<Object> logout = outcomes.get(1);
            assertThat(logout.error()).as("logout 은 항상 성공").isNull();
            Outcome<Object> rotation = outcomes.get(0);
            if (rotation.error() != null) {
                assertThat(rotation.error()).isInstanceOf(UnauthorizedException.class);
            }
            assertThat(refreshTokenRepository.findByUserId(user))
                    .as("반복 %d: 로그아웃 뒤 살아 있는 리프레시 토큰이 없어야 함", i)
                    .isNotEmpty()
                    .allSatisfy(token -> assertThat(token.getRevokedAt()).isNotNull());
            if (rotation.error() == null) {
                String successor = ((RefreshTokenService.Rotation) rotation.value()).refreshToken().refreshToken();
                assertThatThrownBy(() -> refreshTokenService.rotate(successor)).isInstanceOf(UnauthorizedException.class);
            }
        }
    }

    // ---- 동기화: 이중 지급 없음 ----

    @Test
    void identicalConcurrentSyncRequestsAwardXpExactlyOnce() throws Exception {
        String user = "same-request";
        List<Callable<HealthActivitySyncResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < POOL_SIZE; i++) {
            tasks.add(() -> sync(user,
                    ActivityPayloads.steps(8_000),
                    workoutAt(WorkoutType.RUNNING, 30, "08:00", "08:30"),
                    ActivityPayloads.sleep(450, 90, "2026-06-16T22:00:00Z", "2026-06-17T05:30:00Z")));
        }

        List<Outcome<HealthActivitySyncResponse>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).hasSize(POOL_SIZE).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
        int total = outcomes.stream().mapToInt(o -> o.value().gainedXp()).sum();
        assertThat(total).as("걸음 70 + 달리기 105 + 수면 80 이 한 번만 지급").isEqualTo(70 + 105 + 80);
        assertThat(outcomes.stream().filter(o -> o.value().gainedXp() > 0)).hasSize(1);
        assertThat(characterRepository.findByUserId(user).orElseThrow().getTotalXp()).isEqualTo(255);
        assertThat(activityRepository.findByUserIdAndActivityDate(user, DAY)).hasSize(3);
    }

    @Test
    void concurrentStepsUpgradesEndAtTheSameTotalAsSequentialSync() throws Exception {
        String user = "steps-upgrade";

        runConcurrently(List.of(
                () -> sync(user, ActivityPayloads.steps(3_000)),
                () -> sync(user, ActivityPayloads.steps(8_500)),
                () -> sync(user, ActivityPayloads.steps(12_000))));

        // 순서와 무관하게 최종 XP 는 12,000보 기준 (12*5 + 30 = 90)
        assertThat(characterRepository.findByUserId(user).orElseThrow().getTotalXp()).isEqualTo(90);
        assertThat(activityRepository.findByExternalKey(user + "|" + DAY + "|STEPS").orElseThrow().getGainedXp())
                .isEqualTo(90);
        assertThat(activityRepository.findByUserIdAndActivityDate(user, DAY)).hasSize(1);
    }

    @Test
    void concurrentOverlappingWorkoutsAreAcceptedOnlyOnce() throws Exception {
        String user = "overlap-race";

        List<Outcome<HealthActivitySyncResponse>> outcomes = runConcurrently(List.of(
                () -> sync(user, workoutAt(WorkoutType.RUNNING, 30, "08:00", "08:30")),
                () -> sync(user, workoutAt(WorkoutType.CYCLING, 30, "08:10", "08:40"))));

        assertThat(outcomes).hasSize(2).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
        assertThat(outcomes.stream().filter(o -> o.value().gainedXp() > 0)).hasSize(1);
        assertThat(activityRepository.findByUserIdAndActivityDate(user, DAY)).hasSize(1);
    }

    @Test
    void concurrentSyncAcrossDifferentUsersAreIndependent() throws Exception {
        List<Callable<HealthActivitySyncResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < POOL_SIZE * 2; i++) {
            String user = "independent-" + i;
            tasks.add(() -> sync(user, ActivityPayloads.steps(8_000)));
        }

        List<Outcome<HealthActivitySyncResponse>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).hasSize(POOL_SIZE * 2).allSatisfy(o -> assertThat(o.value().gainedXp()).isEqualTo(70));
        for (int i = 0; i < POOL_SIZE * 2; i++) {
            assertThat(characterRepository.findByUserId("independent-" + i)).isPresent();
        }
    }

    // ---- helpers ----

    private HealthActivitySyncResponse sync(String userId, ActivityPayload... activities) {
        return syncService.sync(new HealthActivitySyncRequest(userId, DAY, List.of(activities)));
    }

    private static ActivityPayload workoutAt(WorkoutType type, Integer minutes, String start, String end) {
        return ActivityPayloads.workoutOn(DAY, type, minutes, start, end);
    }

    /** 작업 결과 또는 던진 예외. 예외를 숨기지 않고 테스트가 직접 판단하게 한다. */
    private record Outcome<T>(T value, Throwable error) {
    }

    private <T> List<Outcome<T>> runConcurrently(List<? extends Callable<? extends T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (Callable<? extends T> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return new Outcome<T>(task.call(), null);
                    } catch (Exception e) {
                        return new Outcome<T>(null, e);
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Outcome<T>> results = new ArrayList<>();
            for (Future<Outcome<T>> future : futures) {
                try {
                    results.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    throw new IllegalStateException(e);
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
