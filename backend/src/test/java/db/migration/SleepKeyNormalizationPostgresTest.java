package db.migration;

import com.wellnessgame.postgres.PostgresTestServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2 의 유일 제약 충돌 처리를 실제 PostgreSQL 에서 확인한다. PostgreSQL 은 문장 하나가 실패하면 트랜잭션 전체가 aborted 상태가
 * 되므로, 그룹 단위 savepoint 로 되돌린 뒤에야 같은 트랜잭션에서 legacy 보존과 다른 그룹 정리를 이어 갈 수 있다(H2 는 이 차이가 없다).
 */
@Tag("postgres")
class SleepKeyNormalizationPostgresTest {
    private static final String DAY = "2026-06-17";

    private String database;

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
        database = PostgresTestServer.createDatabase();
        Flyway.configure()
                .dataSource(PostgresTestServer.jdbcUrl(database), PostgresTestServer.username(), PostgresTestServer.password())
                .load()
                .migrate();
    }

    @AfterEach
    void tearDown() {
        PostgresTestServer.dropDatabase(database);
    }

    @Test
    void uniqueConflictRollsBackToSavepointAndTransactionContinues() throws SQLException {
        UUID aCurrent = UUID.randomUUID();
        UUID aLegacy = UUID.randomUUID();
        UUID bLegacy = UUID.randomUUID();
        try (Connection connection = PostgresTestServer.connect(database)) {
            insertSleep(connection, aCurrent, "user-a", V2__sleep_key_normalization.sleepKey("user-a", LocalDate.parse(DAY)), 400, 70);
            insertSleep(connection, aLegacy, "user-a", "user-a|2026-06-16T22:00:00Z|2026-06-17T06:00:00Z|SLEEP", 480, 80);
            insertSleep(connection, bLegacy, "user-b", "user-b|2026-06-16T23:00:00Z|2026-06-17T06:00:00Z|SLEEP", 420, 60);
        }

        // 확인 시점엔 새 키가 없다고 보이게 해 user-a 그룹에서 유일 제약 충돌을 낸다(Flyway 처럼 한 트랜잭션).
        try (Connection connection = PostgresTestServer.connect(database)) {
            connection.setAutoCommit(false);
            new V2__sleep_key_normalization((c, sleepKey) -> false).migrate(connection);
            connection.commit();
        }

        Map<UUID, String> rows = rows();
        assertThat(rows.get(aCurrent)).isEqualTo("user-a|2026-06-17|SLEEP:150");
        assertThat(rows.get(aLegacy)).isEqualTo("user-a|2026-06-16T22:00:00Z|2026-06-17T06:00:00Z|SLEEP|legacy|" + aLegacy + ":0");
        assertThat(rows.get(bLegacy)).isEqualTo("user-b|2026-06-17|SLEEP:60");
    }

    private Map<UUID, String> rows() throws SQLException {
        Map<UUID, String> rows = new LinkedHashMap<>();
        try (Connection connection = PostgresTestServer.connect(database);
             PreparedStatement query = connection.prepareStatement("select id, external_key, gained_xp from health_activity_log");
             ResultSet rs = query.executeQuery()) {
            while (rs.next()) {
                rows.put(rs.getObject(1, UUID.class), rs.getString(2) + ":" + rs.getInt(3));
            }
        }
        return rows;
    }

    private static void insertSleep(Connection connection, UUID id, String userId, String externalKey, int minutes, int gainedXp)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into health_activity_log
                    (id, user_id, activity_date, type, source_type, sleep_minutes, gained_xp, external_key, created_at)
                values (?, ?, date '2026-06-17', 'SLEEP', 'SLEEP', ?, ?, ?, now())
                """)) {
            insert.setObject(1, id);
            insert.setString(2, userId);
            insert.setInt(3, minutes);
            insert.setInt(4, gainedXp);
            insert.setString(5, externalKey);
            insert.executeUpdate();
        }
    }
}
