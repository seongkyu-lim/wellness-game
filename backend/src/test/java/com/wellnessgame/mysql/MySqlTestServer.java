package com.wellnessgame.mysql;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

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
 * MySQL 통합 테스트가 붙는 서버. 기본은 Testcontainers MySQL 8.4 컨테이너이고, 환경변수
 * {@code MYSQL_TEST_JDBC_URL}(예: {@code jdbc:mysql://127.0.0.1:3307/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC})
 * 이 있으면 컨테이너 대신 그 서버를 쓴다(계정은 {@code MYSQL_TEST_USERNAME}·{@code MYSQL_TEST_PASSWORD}, 기본 root/빈 값).
 * URL 의 데이터베이스 경로는 무시한다. 계정에는 데이터베이스 생성·삭제 권한이 있어야 한다.
 *
 * <p>테스트는 {@link #createDatabase()} 로 서로 다른 데이터베이스를 받아 쓰고 끝나면 {@link #dropDatabase(String)} 한다.
 * 같은 서버를 공유해도 스키마가 섞이지 않아 실행 순서에 영향을 받지 않는다.
 */
final class MySqlTestServer {
    private static final Pattern JDBC_URL = Pattern.compile("^(jdbc:mysql://[^/?]+)(?:/[^?]*)?(\\?.*)?$");
    // docker-compose 의 운영 연결 URL 과 같은 옵션
    private static final String DEFAULT_PARAMS = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    private static MySQLContainer<?> container;
    private static String serverUrl; // jdbc:mysql://host:port
    private static String params;
    private static String username;
    private static String password;

    private MySqlTestServer() {
    }

    /** 서버를 준비한다. Docker 도 외부 URL 도 없으면 로컬에서는 건너뛰고, CI 에서는 실패한다. */
    static synchronized void start() {
        if (serverUrl != null) {
            return;
        }
        String external = System.getenv("MYSQL_TEST_JDBC_URL");
        if (external != null && !external.isBlank()) {
            Matcher matcher = JDBC_URL.matcher(external.trim());
            if (!matcher.matches()) {
                throw new IllegalArgumentException("MYSQL_TEST_JDBC_URL 형식이 올바르지 않음: " + external);
            }
            serverUrl = matcher.group(1);
            params = matcher.group(2) != null ? matcher.group(2) : DEFAULT_PARAMS;
            username = envOrDefault("MYSQL_TEST_USERNAME", "root");
            password = envOrDefault("MYSQL_TEST_PASSWORD", "");
            return;
        }
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            String ci = System.getenv("CI");
            if (ci != null && !ci.isBlank()) {
                fail("CI 환경에서는 mysqlTest 를 건너뛰지 않는다: Docker 를 사용할 수 없음");
            }
            assumeTrue(false, "Docker 를 사용할 수 없어 MySQL 통합 테스트를 건너뜀");
        }
        container = new MySQLContainer<>("mysql:8.4.10")
                .withUsername("wellness")
                .withPassword("wellness");
        container.start();
        serverUrl = "jdbc:mysql://" + container.getHost() + ":" + container.getMappedPort(MySQLContainer.MYSQL_PORT);
        params = DEFAULT_PARAMS;
        // Testcontainers 는 root 비밀번호를 앱 계정 비밀번호와 같게 둔다. 테스트마다 DB 를 만들고 지우므로 root 로 붙는다.
        username = "root";
        password = container.getPassword();
    }

    static synchronized void stop() {
        if (container != null) {
            container.stop();
            container = null;
        }
        serverUrl = null;
    }

    /** 새 빈 데이터베이스를 만들고 이름을 돌려준다. */
    static String createDatabase() {
        String name = "test_" + UUID.randomUUID().toString().replace("-", "");
        execute("create database " + name);
        return name;
    }

    static void dropDatabase(String name) {
        execute("drop database if exists " + name);
    }

    static String jdbcUrl(String database) {
        return serverUrl + "/" + database + params;
    }

    static Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), username, password);
    }

    static String username() {
        return username;
    }

    static String password() {
        return password;
    }

    private static void execute(String sql) {
        try (Connection connection = DriverManager.getConnection(serverUrl + "/" + params, username, password);
             Statement statement = connection.createStatement()) {
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
