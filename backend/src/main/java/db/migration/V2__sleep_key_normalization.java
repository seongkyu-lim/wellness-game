package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
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
            Savepoint savepoint = connection.getAutoCommit() ? null : connection.setSavepoint();
            boolean promotedOne;
            try {
                promotedOne = migrateGroup(connection, group.getKey(), rows, false);
            } catch (SQLException e) {
                if (!isIntegrityViolation(e)) {
                    throw e;
                }
                // 확인 뒤 새 키가 생겼다: 이 그룹은 전부 legacy 로 내린다.
                log.warn("수면 키 충돌, 구 형식 행을 모두 legacy 로 보존: key={}", group.getKey());
                if (savepoint != null) {
                    connection.rollback(savepoint);
                }
                promotedOne = migrateGroup(connection, group.getKey(), rows, true);
            }
            promoted += promotedOne ? 1 : 0;
            archived += rows.size() - (promotedOne ? 1 : 0);
        }
        if (promoted + archived > 0) {
            log.info("구 형식 수면 로그 키 정리: 새 키로 전환 {}건, legacy 보존 {}건", promoted, archived);
        }
    }

    /**
     * 키 정리 후보: 이미 legacy 로 바꾼 행과 새 형식으로 보이는 행을 뺀 SLEEP 로그를 새 형식 키별로 묶는다.
     * LIKE 는 대략 거르기만 하고, 새 형식 여부는 계산한 키와 비교해 정확히 확인한다.
     */
    private Map<String, List<SleepRow>> legacyGroupsBySleepKey(Connection connection) throws SQLException {
        Map<String, List<SleepRow>> groups = new LinkedHashMap<>();
        for (SleepRow row : querySleepRows(connection, """
                select id, user_id, activity_date, external_key, sleep_minutes, created_at, gained_xp
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
                update.setBytes(2, toBytes(row.id()));
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
        for (SleepRow row : querySleepRows(connection, """
                select id, user_id, activity_date, external_key, sleep_minutes, created_at, gained_xp
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
                    clearLegacy.setBytes(1, toBytes(row.id()));
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
                        fromBytes(rs.getBytes("id")),
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

    /** MySQL 은 SQLState 23000, H2 는 23505 등 23 계열로 유일 제약 위반을 알린다. */
    private static boolean isIntegrityViolation(SQLException e) {
        return e.getSQLState() != null && e.getSQLState().startsWith("23");
    }

    /** Hibernate 가 UUID 를 binary(16) 에 저장하는 순서(상위 64비트 → 하위 64비트, 빅 엔디언). */
    static byte[] toBytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    static UUID fromBytes(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
