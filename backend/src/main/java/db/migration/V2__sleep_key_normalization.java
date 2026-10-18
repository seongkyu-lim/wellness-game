package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 구 형식 수면 로그 키 정리(#50 → #66 에서 Flyway 로 이전). Flyway 가 한 번만 실행하며, 다시 돌려도 결과가 같다(멱등). 행은 삭제하지 않는다.
 *
 * <p><b>1단계: 키 정리.</b> 수면 externalKey 가 {@code userId|start|end|SLEEP} 에서 {@code userId|yyyy-MM-dd|SLEEP} 로 바뀌어,
 * 구 형식 로그가 있는 날 같은 수면이 새 키로 한 번 더 지급될 수 있다. 구 형식 SLEEP 로그를 (userId, activityDate) 로 묶어
 * <ul>
 *   <li>그날 새 형식 키가 없으면 수면 시간이 가장 긴 행(동률이면 가장 먼저 생성된 행)을 새 형식 키로 바꾸고,</li>
 *   <li>나머지(새 형식 키가 이미 있는 날은 전부)는 {@code <기존키>|legacy|<id>} 로 바꿔 이력으로 남긴다.</li>
 * </ul>
 * 확인 뒤 새 키가 생겨 유일 제약에 걸린 그룹은 그 그룹의 변경을 되돌리고 구 형식 행을 모두 legacy 로 내린다.
 *
 * <p><b>2단계: legacy XP 합치기.</b> 동기화는 새 키 행의 gainedXp 와의 차액만 지급하므로, legacy 행에 남은 XP 는 이후 더 긴
 * 수면이 들어오면 한 번 더 지급될 수 있었다. legacy 행의 gainedXp 를 같은 날 새 키 행으로 옮기고 legacy 행은 0 으로 둔다.
 * 그날 지급 XP 합계(일일 상한 계산)는 바뀌지 않는다. 새 키 행이 없는 날은 그대로 둔다. 이전 버전의 기동 시 마이그레이션으로
 * 이미 키가 정리된 DB 도 이 단계에서 함께 고쳐진다.
 *
 * <p>Flyway 트랜잭션 안에서 실행된다. 유일 제약 충돌 외의 오류는 그대로 던져 마이그레이션 전체를 되돌리고 기동을 멈춘다
 * (한 번만 실행되므로 일부 그룹을 건너뛴 채 완료로 기록하지 않는다).
 */
public class V2__sleep_key_normalization extends BaseJavaMigration {
    private static final Logger log = LoggerFactory.getLogger(V2__sleep_key_normalization.class);
    static final String LEGACY_MARKER = "|legacy|";

    /** 두 단계가 함께 쓰는 SLEEP 로그 조회 컬럼({@link #querySleepRows} 가 이름으로 읽는다). */
    private static final String SLEEP_ROW_COLUMNS =
            "id, user_id, activity_date, external_key, sleep_minutes, created_at, gained_xp";

    private static final Comparator<SleepRow> WINNER_FIRST = Comparator
            .comparingInt(SleepRow::sleepMinutes).reversed()
            .thenComparing(SleepRow::createdAt, Comparator.nullsLast(Comparator.<Timestamp>naturalOrder()))
            .thenComparing(row -> row.id().toString());

    record SleepRow(UUID id, String userId, LocalDate activityDate, String externalKey, int sleepMinutes,
                    Timestamp createdAt, int gainedXp) {
        String sleepKey() {
            return V2__sleep_key_normalization.sleepKey(userId, activityDate);
        }

        String legacyKey() {
            return externalKey + LEGACY_MARKER + id;
        }
    }

    /** 새 키 존재 확인. 테스트에서 "확인 뒤 새 키가 생긴" 경합이나 실패를 재현하려고 바꿔 끼운다. */
    @FunctionalInterface
    interface SleepKeyLookup {
        boolean exists(Connection connection, String sleepKey) throws SQLException;
    }

    private final SleepKeyLookup sleepKeyLookup;

    public V2__sleep_key_normalization() {
        this(V2__sleep_key_normalization::sleepKeyExists);
    }

    V2__sleep_key_normalization(SleepKeyLookup sleepKeyLookup) {
        this.sleepKeyLookup = sleepKeyLookup;
    }

    @Override
    public void migrate(Context context) throws SQLException {
        migrate(context.getConnection());
    }

    void migrate(Connection connection) throws SQLException {
        normalizeKeys(connection);
        consolidateLegacyXp(connection);
    }

    /** 새 수면 키 형식. {@code HealthActivity.sleepKey} 와 같아야 하지만, 마이그레이션은 이후 코드 변경과 무관하도록 따로 둔다. */
    static String sleepKey(String userId, LocalDate activityDate) {
        return String.join("|", userId, activityDate.toString(), "SLEEP");
    }

    private void normalizeKeys(Connection connection) throws SQLException {
        int promoted = 0;
        int archived = 0;
        for (Map.Entry<String, List<SleepRow>> group : legacyGroupsBySleepKey(connection).entrySet()) {
            List<SleepRow> rows = group.getValue().stream().sorted(WINNER_FIRST).toList();
            // 이 마이그레이션은 기동 시(Flyway) 앱이 요청을 받기 전에 돈다. 그래도 롤링 배포 중이면 아직 떠 있는 구버전
            // 인스턴스가 같은 날 새 키 수면 로그를 쓸 수 있어, 존재 확인과 키 변경 사이에 새 키가 생기는 경합이 남는다.
            // 그 경우에만 유일 제약에 걸리므로 그룹 단위 savepoint 로 이 그룹만 되돌리고 전부 legacy 로 내린다.
            // PostgreSQL 은 문장 하나가 실패하면 트랜잭션 전체가 aborted 상태가 되어 savepoint 로 되돌리기 전에는
            // 어떤 문장도 받지 않는다. 그래서 충돌 처리(전부 legacy 로 내리기)도 반드시 savepoint 복구 뒤에 한다.
            String sleepKey = group.getKey();
            boolean promotedOne = withSavepoint(connection,
                    () -> migrateGroup(connection, sleepKey, rows, false),
                    () -> {
                        // 확인 뒤 새 키가 생겼다: 이 그룹은 전부 legacy 로 내린다.
                        log.warn("수면 키 충돌, 구 형식 행을 모두 legacy 로 보존: key={}", sleepKey);
                        return migrateGroup(connection, sleepKey, rows, true);
                    });
            promoted += promotedOne ? 1 : 0;
            archived += rows.size() - (promotedOne ? 1 : 0);
        }
        if (promoted + archived > 0) {
            log.info("구 형식 수면 로그 키 정리: 새 키로 전환 {}건, legacy 보존 {}건", promoted, archived);
        }
    }

    /** SQLException 을 던질 수 있는 작업. */
    @FunctionalInterface
    interface SqlWork<T> {
        T run() throws SQLException;
    }

    /**
     * savepoint 를 잡고 {@code work} 를 실행한다. 성공하면 savepoint 를 해제하고(PostgreSQL 서브트랜잭션이 쌓이지 않게),
     * 무결성 제약 위반이면 savepoint 로 되돌린 뒤 {@code onIntegrityViolation} 을 실행한다. 그 외 오류는 그대로 던진다.
     * 자동 커밋 연결이면 savepoint 없이 실행한다(실패한 문장만 되돌려진다).
     */
    static <T> T withSavepoint(Connection connection, SqlWork<T> work, SqlWork<T> onIntegrityViolation) throws SQLException {
        Savepoint savepoint = connection.getAutoCommit() ? null : connection.setSavepoint();
        T result;
        try {
            result = work.run();
        } catch (SQLException e) {
            if (!isIntegrityViolation(e)) {
                throw e;
            }
            if (savepoint != null) {
                connection.rollback(savepoint);
            }
            return onIntegrityViolation.run();
        }
        if (savepoint != null) {
            connection.releaseSavepoint(savepoint);
        }
        return result;
    }

    /**
     * 키 정리 후보: 이미 legacy 로 바꾼 행과 새 형식으로 보이는 행을 뺀 SLEEP 로그를 새 형식 키별로 묶는다.
     * LIKE 는 대략 거르기만 하고, 새 형식 여부는 계산한 키와 비교해 정확히 확인한다.
     *
     * <p>{@code '%-__|SLEEP'} 의 {@code _} 는 임의의 한 글자 와일드카드다(이스케이프하지 않음). 날짜 끝자리
     * ({@code -17|SLEEP})를 노린 패턴이라 그 외에도 "-두 글자|SLEEP" 으로 끝나는 키는 후보에서 빠질 수 있다.
     * 구 형식 키는 {@code ...Z|SLEEP} 처럼 ISO 시각으로 끝나므로 빠지지 않는다. 반대로 통과한 행은 아래에서
     * 계산한 새 키와 정확히 비교해 이미 새 형식인 행을 다시 거른다. 즉 LIKE 는 스캔 범위만 줄이고 정확성은 재검증이 책임진다.
     */
    private Map<String, List<SleepRow>> legacyGroupsBySleepKey(Connection connection) throws SQLException {
        Map<String, List<SleepRow>> groups = new LinkedHashMap<>();
        for (SleepRow row : querySleepRows(connection, "select " + SLEEP_ROW_COLUMNS + """
                 from health_activity_log
                where type = 'SLEEP'
                  and external_key not like '%|legacy|%'
                  and external_key not like '%-__|SLEEP'
                """)) {
            if (!row.externalKey().equals(row.sleepKey())) {
                groups.computeIfAbsent(row.sleepKey(), key -> new ArrayList<>()).add(row);
            }
        }
        return groups;
    }

    /**
     * 한 그룹의 키를 바꾼다. 승자를 새 키로 바꿨으면 true.
     *
     * @param rows       승자 우선으로 정렬된 구 형식 행
     * @param archiveAll true 면 새 키 존재 여부와 무관하게 모두 legacy 로 바꾼다
     */
    private boolean migrateGroup(Connection connection, String sleepKey, List<SleepRow> rows, boolean archiveAll)
            throws SQLException {
        boolean promote = !archiveAll && !sleepKeyLookup.exists(connection, sleepKey);
        try (PreparedStatement update = connection.prepareStatement(
                "update health_activity_log set external_key = ? where id = ?")) {
            for (int i = 0; i < rows.size(); i++) {
                SleepRow row = rows.get(i);
                update.setString(1, promote && i == 0 ? sleepKey : row.legacyKey());
                update.setObject(2, row.id());
                update.executeUpdate();
            }
        }
        return promote;
    }

    static boolean sleepKeyExists(Connection connection, String sleepKey) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "select count(*) from health_activity_log where external_key = ?")) {
            query.setString(1, sleepKey);
            try (ResultSet rs = query.executeQuery()) {
                rs.next();
                return rs.getLong(1) > 0;
            }
        }
    }

    private void consolidateLegacyXp(Connection connection) throws SQLException {
        Map<String, List<SleepRow>> groups = new LinkedHashMap<>();
        for (SleepRow row : querySleepRows(connection, "select " + SLEEP_ROW_COLUMNS + """
                 from health_activity_log
                where type = 'SLEEP'
                  and external_key like '%|legacy|%'
                  and gained_xp <> 0
                """)) {
            groups.computeIfAbsent(row.sleepKey(), key -> new ArrayList<>()).add(row);
        }

        int moved = 0;
        try (PreparedStatement addToCurrent = connection.prepareStatement(
                "update health_activity_log set gained_xp = gained_xp + ? where external_key = ? and type = 'SLEEP'");
             PreparedStatement clearLegacy = connection.prepareStatement(
                     "update health_activity_log set gained_xp = 0 where id = ?")) {
            for (Map.Entry<String, List<SleepRow>> group : groups.entrySet()) {
                int legacyXp = group.getValue().stream().mapToInt(SleepRow::gainedXp).sum();
                addToCurrent.setInt(1, legacyXp);
                addToCurrent.setString(2, group.getKey());
                if (addToCurrent.executeUpdate() == 0) {
                    log.warn("legacy 수면 XP 를 옮길 새 키 행이 없어 그대로 둠: key={}", group.getKey());
                    continue;
                }
                for (SleepRow row : group.getValue()) {
                    clearLegacy.setObject(1, row.id());
                    clearLegacy.executeUpdate();
                }
                moved += group.getValue().size();
            }
        }
        if (moved > 0) {
            log.info("legacy 수면 로그 XP 를 새 키 행으로 합침: {}건", moved);
        }
    }

    private static List<SleepRow> querySleepRows(Connection connection, String sql) throws SQLException {
        List<SleepRow> rows = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(sql); ResultSet rs = query.executeQuery()) {
            while (rs.next()) {
                rows.add(new SleepRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("user_id"),
                        rs.getObject("activity_date", LocalDate.class),
                        rs.getString("external_key"),
                        rs.getInt("sleep_minutes"), // NULL 은 0
                        rs.getTimestamp("created_at"),
                        rs.getInt("gained_xp")));
            }
        }
        return rows;
    }

    /** PostgreSQL·H2 모두 SQLState 23505 등 23 계열(무결성 제약 위반)로 유일 제약 위반을 알린다. */
    private static boolean isIntegrityViolation(SQLException e) {
        return e.getSQLState() != null && e.getSQLState().startsWith("23");
    }
}
