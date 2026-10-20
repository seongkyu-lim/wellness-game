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
 * Supabase 데이터 API 역할(anon, authenticated)의 권한을 앱 스키마에서 걷어 낸다(#104).
 *
 * <p>V4 의 RLS(허용 정책 없음)가 행 접근을 막지만, Supabase 는 public 스키마의 테이블·시퀀스·함수에 이 역할들의 권한을
 * 기본으로 준다. RLS 가 실수로 꺼지거나 정책이 추가돼도 노출되지 않도록 권한 자체를 없앤다(심층 방어).
 * 백엔드는 테이블 소유자로 접속하므로 영향이 없다.
 *
 * <ul>
 *   <li>역할이 있을 때만 실행한다(로컬·테스트 PostgreSQL 에는 보통 없다). 있는 역할만 대상으로 한다.</li>
 *   <li>테이블: {@code flyway_schema_history} 를 뺀 테이블마다 {@code revoke all} 한다. {@code on all tables in schema} 를 쓰면
 *       이 테이블도 포함되는데, 마이그레이션 중 Flyway 가 다른 연결로 이 테이블을 잡고 있어 V4 의 ALTER 처럼 서로를 기다릴 수 있다
 *       (GRANT/REVOKE 는 pg_class 행을 고치며 그 테이블 잠금과 겹칠 수 있다). 이 테이블은 Supabase Data API 비활성화로 막는다.</li>
 *   <li>시퀀스·함수: 스키마 단위로 걷어 낸다(flyway_schema_history 는 시퀀스를 쓰지 않는다). 함수의 PUBLIC 실행 권한은 건드리지 않는다.</li>
 *   <li>기본 권한: 마이그레이션을 실행하는 역할이 앞으로 만들 테이블·시퀀스·함수에도 권한이 붙지 않게 {@code alter default privileges}
 *       로 걷어 낸다. 다른 역할(예: supabase_admin)이 정한 기본 권한은 바꿀 수 없으므로 그 역할로 만든 객체는 대상이 아니다.</li>
 * </ul>
 *
 * <p>H2(기본 테스트)는 이 역할·문법이 없어 PostgreSQL 에서만 실행한다.
 */
public class V6__revoke_supabase_api_role_grants extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
            return;
        }
        List<String> statements = new ArrayList<>();
        try (Statement statement = connection.createStatement()) {
            String roles = existingRoles(statement);
            if (roles == null) {
                return;
            }
            // %I 로 식별자를 안전하게 인용한 문장을 DB 가 만들게 한다. roles 는 pg_roles 에서 quote_ident 로 만든 값이다.
            try (ResultSet rows = statement.executeQuery(
                    "select format('revoke all on table %I from " + roles + "', tablename) "
                            + "from pg_tables where schemaname = current_schema() and tablename <> 'flyway_schema_history' "
                            + "order by tablename")) {
                while (rows.next()) {
                    statements.add(rows.getString(1));
                }
            }
            try (ResultSet rows = statement.executeQuery("select quote_ident(current_schema())")) {
                rows.next();
                String schema = rows.getString(1);
                statements.add("revoke all on all sequences in schema " + schema + " from " + roles);
                statements.add("revoke all on all functions in schema " + schema + " from " + roles);
                statements.add("alter default privileges in schema " + schema + " revoke all on tables from " + roles);
                statements.add("alter default privileges in schema " + schema + " revoke all on sequences from " + roles);
                statements.add("alter default privileges in schema " + schema + " revoke all on functions from " + roles);
            }
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /** 있는 API 역할을 인용해 쉼표로 이은 값. 하나도 없으면 null. */
    private static String existingRoles(Statement statement) throws SQLException {
        List<String> found = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(
                "select quote_ident(rolname) from pg_roles where rolname in ('anon', 'authenticated') order by rolname")) {
            while (rows.next()) {
                found.add(rows.getString(1));
            }
        }
        return found.isEmpty() ? null : String.join(", ", found);
    }
}
