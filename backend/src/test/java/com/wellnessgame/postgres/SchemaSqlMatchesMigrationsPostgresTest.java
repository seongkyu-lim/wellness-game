package com.wellnessgame.postgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #98 참고·수동 세팅용 최신 스키마 파일({@code backend/db/schema.sql})이 Flyway 마이그레이션 결과와 같은지 검사한다.
 * 빈 DB 두 개에 각각 Flyway migrate 와 schema.sql 을 적용한 뒤 컬럼·제약·인덱스·RLS 를 비교한다({@code flyway_schema_history} 제외).
 * 마이그레이션을 추가하고 schema.sql 을 갱신하지 않으면 실패한다.
 */
@Tag("postgres")
class SchemaSqlMatchesMigrationsPostgresTest {
    /** Gradle 테스트 작업 디렉터리는 backend 프로젝트 디렉터리다. */
    private static final Path SCHEMA_SQL = Path.of("db", "schema.sql");

    /** 비교 항목별 조회. 결과 한 행은 한 문자열로 이어 붙여 비교한다. */
    private static final Map<String, String> SHAPE_QUERIES = new LinkedHashMap<>();

    static {
        SHAPE_QUERIES.put("columns", """
                select concat_ws(' | ', table_name, column_name, data_type, udt_name, character_maximum_length,
                                 numeric_precision, numeric_scale, datetime_precision, is_nullable,
                                 coalesce(column_default, '<no default>'))
                from information_schema.columns
                where table_schema = current_schema() and table_name <> 'flyway_schema_history'
                order by table_name, column_name
                """);
        SHAPE_QUERIES.put("constraints", """
                select concat_ws(' | ', t.relname, c.conname, c.contype, pg_get_constraintdef(c.oid))
                from pg_constraint c
                join pg_class t on t.oid = c.conrelid
                join pg_namespace n on n.oid = t.relnamespace
                where n.nspname = current_schema() and t.relname <> 'flyway_schema_history'
                order by t.relname, c.conname
                """);
        // indexdef 의 스키마 한정자(예: ON public.refresh_token)는 지워 스키마 이름과 무관하게 비교한다.
        SHAPE_QUERIES.put("indexes", """
                select concat_ws(' | ', tablename, indexname,
                                 replace(indexdef, ' ON ' || quote_ident(schemaname) || '.', ' ON '))
                from pg_indexes
                where schemaname = current_schema() and tablename <> 'flyway_schema_history'
                order by tablename, indexname
                """);
        SHAPE_QUERIES.put("row level security", """
                select concat_ws(' | ', c.relname, 'relrowsecurity=' || c.relrowsecurity,
                                 'relforcerowsecurity=' || c.relforcerowsecurity)
                from pg_class c
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = current_schema() and c.relkind in ('r', 'p') and c.relname <> 'flyway_schema_history'
                order by c.relname
                """);
    }

    private final List<String> databases = new ArrayList<>();

    @BeforeAll
    static void startPostgres() {
        PostgresTestServer.start();
    }

    @AfterAll
    static void stopPostgres() {
        PostgresTestServer.stop();
    }

    @AfterEach
    void tearDown() {
        databases.forEach(PostgresTestServer::dropDatabase);
    }

    @Test
    void schemaSqlMatchesFlywayMigrations() throws Exception {
        String flywayDb = newDatabase();
        Flyway.configure()
                .dataSource(PostgresTestServer.jdbcUrl(flywayDb), PostgresTestServer.username(), PostgresTestServer.password())
                .load()
                .migrate();

        String schemaSqlDb = newDatabase();
        applySchemaSql(schemaSqlDb);

        Map<String, List<String>> expected = schemaShape(flywayDb);
        Map<String, List<String>> actual = schemaShape(schemaSqlDb);

        assertThat(expected.get("columns")).as("Flyway 로 만든 스키마에 테이블이 있어야 한다").isNotEmpty();
        assertThat(differences(expected, actual))
                .as("backend/db/schema.sql 이 Flyway 마이그레이션 결과와 다르다. 마이그레이션에 맞게 schema.sql 을 갱신하라")
                .isEmpty();
    }

    private String newDatabase() {
        String name = PostgresTestServer.createDatabase();
        databases.add(name);
        return name;
    }

    private static void applySchemaSql(String database) throws IOException, SQLException {
        assertThat(SCHEMA_SQL).as("schema.sql 위치(작업 디렉터리 기준)").exists();
        String sql = Files.readString(SCHEMA_SQL);
        try (Connection connection = PostgresTestServer.connect(database);
             Statement statement = connection.createStatement()) {
            statement.execute(sql); // PgJDBC 는 여러 문장을 나눠 차례로 실행한다(자동 커밋).
        }
    }

    private static Map<String, List<String>> schemaShape(String database) throws SQLException {
        Map<String, List<String>> shape = new LinkedHashMap<>();
        try (Connection connection = PostgresTestServer.connect(database)) {
            for (Map.Entry<String, String> query : SHAPE_QUERIES.entrySet()) {
                List<String> rows = new ArrayList<>();
                try (Statement statement = connection.createStatement();
                     ResultSet rs = statement.executeQuery(query.getValue())) {
                    while (rs.next()) {
                        rows.add(rs.getString(1));
                    }
                }
                shape.put(query.getKey(), rows);
            }
        }
        return shape;
    }

    /** 항목별로 한쪽에만 있는 행을 사람이 읽을 수 있게 모은다. 같으면 빈 목록. */
    private static List<String> differences(Map<String, List<String>> flyway, Map<String, List<String>> schemaSql) {
        List<String> differences = new ArrayList<>();
        for (String category : SHAPE_QUERIES.keySet()) {
            List<String> expected = flyway.get(category);
            List<String> actual = schemaSql.get(category);
            expected.stream().filter(row -> !actual.contains(row))
                    .forEach(row -> differences.add("[" + category + "] Flyway 에만 있음: " + row));
            actual.stream().filter(row -> !expected.contains(row))
                    .forEach(row -> differences.add("[" + category + "] schema.sql 에만 있음: " + row));
        }
        return differences;
    }
}
