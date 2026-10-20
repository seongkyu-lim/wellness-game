package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 스키마의 모든 테이블에 RLS 를 켠다(허용 정책 없음, #94).
 *
 * <p>Supabase 는 public 스키마를 데이터 API(PostgREST)로 노출하므로 RLS 가 꺼져 있으면 anon 키만으로 모든 행을 읽고 쓸 수 있다.
 * 백엔드는 테이블 소유자로 접속해 RLS 를 적용받지 않으므로(FORCE 미사용) 동작은 그대로이고, 데이터 API 경로만 막힌다.
 * 이후 마이그레이션에서 테이블을 추가하면 그 마이그레이션에서도 RLS 를 켜야 한다(PostgresIntegrationTest 가 검사).
 *
 * <p>{@code flyway_schema_history} 는 제외한다. 마이그레이션 중 Flyway 가 다른 연결로 이 테이블을 잡고 있어
 * ALTER TABLE(ACCESS EXCLUSIVE 잠금)이 서로를 기다리며 멈춘다(교착). 이 테이블은 스키마 이력만 담으며 Supabase Data API 비활성화로 막는다.
 *
 * <p>H2(기본 테스트)는 RLS 문법이 없어 PostgreSQL 에서만 실행한다.
 */
public class V4__enable_row_level_security extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
            return;
        }
        // %I 로 식별자를 안전하게 인용한 ALTER 문을 DB 가 만들게 한다.
        List<String> statements = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select format('alter table %I enable row level security', tablename) "
                             + "from pg_tables where schemaname = current_schema() and tablename <> 'flyway_schema_history' "
                             + "order by tablename")) {
            while (rows.next()) {
                statements.add(rows.getString(1));
            }
        }
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }
}
