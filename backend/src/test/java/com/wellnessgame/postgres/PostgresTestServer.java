package com.wellnessgame.postgres;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PostgreSQL 통합 테스트가 붙는 서버. 기본은 Testcontainers PostgreSQL 17.6 컨테이너(운영 Supabase 와 같은 17.6 계열, docker-compose 와 같은 태그)이고, 환경변수
 * {@code POSTGRES_TEST_JDBC_URL}(예: {@code jdbc:postgresql://127.0.0.1:5432/postgres})이 있으면 컨테이너 대신 그 서버를 쓴다
 * (계정은 {@code POSTGRES_TEST_USERNAME}·{@code POSTGRES_TEST_PASSWORD}, 기본 postgres/빈 값). URL 의 데이터베이스는
 * 테스트 DB 를 만들고 지울 때 붙는 관리용 DB 로만 쓴다. 계정에는 데이터베이스 생성·삭제 권한이 있어야 한다.
 *
 * <p>테스트는 {@link #createDatabase()} 로 서로 다른 데이터베이스를 받아 쓰고 끝나면 {@link #dropDatabase(String)} 한다.
 * 같은 서버를 공유해도 스키마가 섞이지 않아 실행 순서에 영향을 받지 않는다.
 */
public final class PostgresTestServer {
    private static final Pattern JDBC_URL = Pattern.compile("^(jdbc:postgresql://[^/?]+)(?:/([^?]*))?(\\?.*)?$");

    private static PostgreSQLContainer<?> container;
    private static String serverUrl; // jdbc:postgresql://host:port
    private static String adminDatabase;
    private static String params;
    private static String username;
    private static String password;

    private PostgresTestServer() {
    }

    /** 서버를 준비한다. Docker 도 외부 URL 도 없으면 로컬에서는 건너뛰고, CI 에서는 실패한다. */
    public static synchronized void start() {
        if (serverUrl != null) {
            return;
        }
        String external = System.getenv("POSTGRES_TEST_JDBC_URL");
        if (external != null && !external.isBlank()) {
            Matcher matcher = JDBC_URL.matcher(external.trim());
            if (!matcher.matches()) {
                throw new IllegalArgumentException("POSTGRES_TEST_JDBC_URL 형식이 올바르지 않음: " + external);
            }
            serverUrl = matcher.group(1);
            adminDatabase = matcher.group(2) == null || matcher.group(2).isBlank() ? "postgres" : matcher.group(2);
            params = matcher.group(3) != null ? matcher.group(3) : "";
            username = envOrDefault("POSTGRES_TEST_USERNAME", "postgres");
            password = envOrDefault("POSTGRES_TEST_PASSWORD", "");
            return;
        }
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            String ci = System.getenv("CI");
            if (ci != null && !ci.isBlank()) {
                fail("CI 환경에서는 postgresTest 를 건너뛰지 않는다: Docker 를 사용할 수 없음");
            }
            assumeTrue(false, "Docker 를 사용할 수 없어 PostgreSQL 통합 테스트를 건너뜀");
        }
        container = new PostgreSQLContainer<>("postgres:17.6")
                .withDatabaseName("wellness_admin")
                .withUsername("wellness")
                .withPassword("wellness");
        container.start();
        serverUrl = "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
        adminDatabase = container.getDatabaseName();
        params = "";
        // 컨테이너의 POSTGRES_USER 는 슈퍼유저라 테스트마다 데이터베이스를 만들고 지울 수 있다.
        username = container.getUsername();
        password = container.getPassword();
    }

    public static synchronized void stop() {
        if (container != null) {
            container.stop();
            container = null;
        }
        serverUrl = null;
    }

    /** 새 빈 데이터베이스를 만들고 이름을 돌려준다. */
    public static String createDatabase() {
        String name = "test_" + UUID.randomUUID().toString().replace("-", "");
        execute("create database " + name);
        return name;
    }

    public static void dropDatabase(String name) {
        // 닫히지 않은 연결이 남아 있어도 지운다(PostgreSQL 13+). WITH (FORCE) 는 다른 세션을 끊으므로
        // 접속 계정이 그 DB 의 소유자(또는 슈퍼유저)여야 한다. POSTGRES_TEST_JDBC_URL 로 외부 서버를 쓸 때는
        // 이 계정으로 createDatabase() 가 만든 DB 만 지우므로 소유자 조건은 맞지만, 다른 세션 종료 권한
        // (pg_signal_backend 등)이 없는 관리형 서버에서는 실패할 수 있다.
        execute("drop database if exists " + name + " with (force)");
    }

    public static String jdbcUrl(String database) {
        return serverUrl + "/" + database + params;
    }

    public static Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), username, password);
    }

    public static String username() {
        return username;
    }

    public static String password() {
        return password;
    }

    private static void execute(String sql) {
        // create/drop database 는 트랜잭션 안에서 실행할 수 없다(자동 커밋 연결).
        try (Connection connection = connect(adminDatabase); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value != null ? value : defaultValue;
    }
}
