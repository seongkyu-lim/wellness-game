package com.wellnessgame.activity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 구 형식 수면 로그 키 정리(#50). 기동 시 한 번 실행되며 멱등하다. 행은 삭제하지 않는다.
 *
 * <p>수면 externalKey 가 {@code userId|start|end|SLEEP} 에서 {@link HealthActivity#sleepKey} 형식으로 바뀌어,
 * 구 형식 로그가 있는 날 같은 수면이 새 키로 한 번 더 지급될 수 있다. 구 형식 SLEEP 로그를 (userId, activityDate) 로 묶어
 * <ul>
 *   <li>그날 새 형식 키가 없으면 수면 시간이 가장 긴 행(동률이면 가장 먼저 생성된 행)을 새 형식 키로 바꾸고,</li>
 *   <li>나머지(새 형식 키가 이미 있는 날은 전부)는 {@code <기존키>|legacy|<id>} 로 바꿔 이력으로 남긴다.</li>
 * </ul>
 * legacy 행도 gainedXp 는 그대로라 일일 XP 합계에 포함된다. legacy 키는 다음 실행에서 후보가 아니므로 재실행해도 변화가 없다.
 *
 * <p>기동을 막지 않도록 그룹(사용자·날짜)마다 트랜잭션을 나누고, 실패한 그룹은 로그만 남기고 다음 그룹으로 넘어간다.
 * 그사이 새 키가 생겨 유일 제약에 걸린 그룹은 구 형식 행을 모두 legacy 로 내린다.
 */
@Component
@ConditionalOnProperty(name = "app.migrations.sleep-keys.enabled", havingValue = "true", matchIfMissing = true)
public class SleepKeyMigration implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SleepKeyMigration.class);
    static final String LEGACY_MARKER = "|legacy|";

    private static final Comparator<HealthActivity> WINNER_FIRST = Comparator
            .comparingInt((HealthActivity a) -> a.getSleepMinutes() == null ? 0 : a.getSleepMinutes()).reversed()
            .thenComparing(HealthActivity::getCreatedAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
            .thenComparing(a -> a.getId().toString());

    private final HealthActivityRepository activityRepository;
    private final TransactionTemplate transaction;

    public SleepKeyMigration(HealthActivityRepository activityRepository, PlatformTransactionManager transactionManager) {
        this.activityRepository = activityRepository;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, List<HealthActivity>> groups;
        try {
            groups = legacyGroupsBySleepKey();
        } catch (RuntimeException e) {
            log.error("구 형식 수면 로그 키 정리 건너뜀: 후보 조회 실패", e);
            return;
        }

        int promoted = 0;
        int archived = 0;
        int failed = 0;
        for (Map.Entry<String, List<HealthActivity>> group : groups.entrySet()) {
            List<HealthActivity> legacyRows = group.getValue().stream().sorted(WINNER_FIRST).toList();
            try {
                boolean promotedOne = migrateGroup(group.getKey(), legacyRows, false);
                promoted += promotedOne ? 1 : 0;
                archived += legacyRows.size() - (promotedOne ? 1 : 0);
            } catch (DataIntegrityViolationException conflict) {
                // 확인 뒤 새 키가 생겼다: 이 그룹은 전부 legacy 로 내린다.
                log.warn("수면 키 충돌, 구 형식 행을 모두 legacy 로 보존: key={}", group.getKey());
                try {
                    migrateGroup(group.getKey(), legacyRows, true);
                    archived += legacyRows.size();
                } catch (RuntimeException e) {
                    failed++;
                    log.error("수면 키 정리 실패(건너뜀): key={}", group.getKey(), e);
                }
            } catch (RuntimeException e) {
                failed++;
                log.error("수면 키 정리 실패(건너뜀): key={}", group.getKey(), e);
            }
        }
        if (promoted + archived + failed > 0) {
            log.info("구 형식 수면 로그 키 정리: 새 키로 전환 {}건, legacy 보존 {}건, 실패 그룹 {}건", promoted, archived, failed);
        }
    }

    /** 새 형식 수면 키별로 묶은 구 형식 SLEEP 로그. */
    private Map<String, List<HealthActivity>> legacyGroupsBySleepKey() {
        Map<String, List<HealthActivity>> groups = new LinkedHashMap<>();
        for (HealthActivity activity : activityRepository.findSleepKeyMigrationCandidates()) {
            String sleepKey = HealthActivity.sleepKey(activity.getUserId(), activity.getActivityDate());
            if (!activity.getExternalKey().equals(sleepKey)) {
                groups.computeIfAbsent(sleepKey, key -> new ArrayList<>()).add(activity);
            }
        }
        return groups;
    }

    /**
     * 한 그룹을 자체 트랜잭션에서 정리한다. 승자를 새 키로 바꿨으면 true.
     *
     * @param legacyRows 승자 우선으로 정렬된 구 형식 행
     * @param archiveAll true 면 새 키 존재 여부와 무관하게 모두 legacy 로 바꾼다
     */
    private boolean migrateGroup(String sleepKey, List<HealthActivity> legacyRows, boolean archiveAll) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            boolean promote = !archiveAll && !activityRepository.existsByExternalKey(sleepKey);
            for (int i = 0; i < legacyRows.size(); i++) {
                HealthActivity row = legacyRows.get(i);
                String key = promote && i == 0 ? sleepKey : row.getExternalKey() + LEGACY_MARKER + row.getId();
                activityRepository.updateExternalKey(row.getId(), key);
            }
            return promote;
        }));
    }
}
