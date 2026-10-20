package com.wellnessgame.postgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #104 V6: Supabase 데이터 API 역할(anon, authenticated)이 있으면 앱 테이블·시퀀스·함수 권한과 기본 권한을 걷어 낸다.
 * Supabase 처럼 마이그레이션 전에 기본 권한(alter default privileges ... grant)을 준 DB 에서 Flyway 를 돌려 확인한다.
 * 역할은 클러스터 전역이라 없을 때만 만든다(만들 수 없는 외부 서버면 건너뜀).
 */
@Tag("postgres")
class ApiRoleGrantsPostgresTest {
    private static final List<String> ROLES = List.of("anon", "authenticated");

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
    void migrationRevokesApiRolePrivilegesOnAppObjectsAndFutureTables() throws SQLException {
        String database = PostgresTestServer.createDatabase();
        databases.add(database);
        try (Connection connection = PostgresTestServer.connect(database);
             Statement statement = connection.createStatement()) {
            assumeTrue(createRolesIfMissing(statement), "anon·authenticated 역할을 만들 수 없어 건너뜀");
            // Supabase 와 같게: 이후 만드는 객체에 API 역할 권한이 자동으로 붙는다.
            statement.execute("alter default privileges in schema public grant all on tables to anon, authenticated");
            statement.execute("alter default privileges in schema public grant all on sequences to anon, authenticated");
            statement.execute("alter default privileges in schema public grant all on functions to anon, authenticated");
        }

        Flyway.configure()
                .dataSource(PostgresTestServer.jdbcUrl(database), PostgresTestServer.username(), PostgresTestServer.password())
                .load()
                .migrate();

        try (Connection connection = PostgresTestServer.connect(database);
             Statement statement = connection.createStatement()) {
            assertThat(query(statement, """
                    select format('%s %s', grantee, table_name)
                    from information_schema.role_table_grants
                    where table_schema = current_schema() and grantee in ('anon', 'authenticated')
                      and table_name <> 'flyway_schema_history'
                    """)).as("앱 테이블에 남은 API 역할 권한").isEmpty();
            assertThat(query(statement, """
                    select defaclobjtype || ' ' || defaclacl::text from pg_default_acl
                    where defaclacl::text like '%anon=%' or defaclacl::text like '%authenticated=%'
                    """)).as("API 역할에 남은 기본 권한").isEmpty();

            // 마이그레이션 뒤에 만드는 테이블에도 권한이 붙지 않는다.
            statement.execute("create table v6_future_table (id integer)");
            for (String role : ROLES) {
                assertThat(query(statement,
                        "select has_table_privilege('" + role + "', 'v6_future_table', 'SELECT')::text"))
                        .as(role).containsExactly("false");
            }
        }
    }

    @Test
    void migrationIsNoOpWithoutApiRoles() throws SQLException {
        // 역할이 없는 DB 에서도 실패하지 않는다(다른 테스트가 역할을 만들었으면 이 검사는 위 테스트와 같아진다).
        String database = PostgresTestServer.createDatabase();
        databases.add(database);
        Flyway.configure()
                .dataSource(PostgresTestServer.jdbcUrl(database), PostgresTestServer.username(), PostgresTestServer.password())
                .load()
                .migrate();
    }

    private static boolean createRolesIfMissing(Statement statement) {
        try {
            for (String role : ROLES) {
                statement.execute("do $$ begin if not exists (select 1 from pg_roles where rolname = '" + role + "') "
                        + "then create role " + role + " nologin; end if; end $$");
            }
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static List<String> query(Statement statement, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
        }
        return rows;
    }
}
