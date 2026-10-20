package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * #103·#105: 리프레시 토큰 family 의 권한 범위, 유예 재발급 1회 제한, 조회 전용 액세스 토큰 무효화 기준.
 *
 * <ul>
 *   <li>scope: 이 family 가 회전으로 다시 발급하는 액세스 토큰 범위. 'full'(앱, 기존 행) 또는 'read'(웹 코드 교환 로그인).</li>
 *   <li>grace_reissued: 이 토큰이 회전된 뒤 유예 구간 재발급을 이미 한 번 했는지. 두 번째는 재사용 공격으로 보고 family 를 폐기한다.</li>
 *   <li>read_tokens_valid_after: 조회 전용(scope=read) 액세스 토큰만의 무효화 기준(앱 로그아웃). null 이면 없음.</li>
 * </ul>
 * 컬럼 이름·값은 다른 서버 구현과 공유하므로 바꾸지 않는다.
 *
 * <p>SQL 이 아니라 Java 마이그레이션인 이유: 운영 중인 테이블에 ALTER(ACCESS EXCLUSIVE 잠금)를 걸 때 오래 기다리며 다른 요청을
 * 막지 않도록 PostgreSQL 에서만 {@code set local lock_timeout} 을 먼저 건다(H2 는 이 문법이 없다). 넘으면 마이그레이션이 실패하고
 * 롤백되므로 다시 배포하면 된다. 나머지 문장은 PostgreSQL 과 H2(PostgreSQL 모드)에서 그대로 실행되는 공통 문법이다.
 */
public class V5__token_scope_and_read_cutoff extends BaseJavaMigration {
    static final List<String> STATEMENTS = List.of(
            "alter table refresh_token add column scope varchar(16) default 'full' not null",
            "alter table refresh_token add column grace_reissued boolean default false not null",
            "alter table refresh_token add constraint ck_refresh_token_scope check (scope in ('full', 'read'))",
            "alter table user_auth_state add column read_tokens_valid_after timestamp(6) with time zone");

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            if ("PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
                // local: 이 마이그레이션 트랜잭션에만 적용된다(풀에 돌아간 연결에 남지 않음).
                statement.execute("set local lock_timeout = '5s'");
            }
            for (String sql : STATEMENTS) {
                statement.execute(sql);
            }
        }
    }
}
