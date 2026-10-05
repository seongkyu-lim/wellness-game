package com.wellnessgame.activity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 구 형식 수면 로그 키 정리(#50). 기동 시 한 번 실행되며 멱등하다. 행은 삭제하지 않는다.
 *
 * <p>수면 externalKey 가 {@code userId|start|end|SLEEP} 에서 {@code userId|date|SLEEP} 로 바뀌어, 구 형식 로그가 있는 날
 * 같은 수면이 새 키로 한 번 더 지급될 수 있다. 구 형식 SLEEP 로그를 (userId, activityDate) 로 묶어
 * <ul>
 *   <li>그날 새 형식 키가 없으면 수면 시간이 가장 긴 행(동률이면 가장 먼저 생성된 행)을 새 형식 키로 바꾸고,</li>
 *   <li>나머지(새 형식 키가 이미 있는 날은 전부)는 {@code <기존키>|legacy|<id>} 로 바꿔 이력으로 남긴다.</li>
 * </ul>
 * legacy 행도 gainedXp 는 그대로라 일일 XP 합계에 포함된다. legacy 키는 다음 실행에서 후보가 아니므로 재실행해도 변화가 없다.
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

    public SleepKeyMigration(HealthActivityRepository activityRepository) {
        this.activityRepository = activityRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Map<String, List<HealthActivity>> groups = new LinkedHashMap<>();
        for (HealthActivity activity : activityRepository.findSleepKeyMigrationCandidates()) {
            if (!activity.getExternalKey().equals(newKey(activity.getUserId(), activity.getActivityDate()))) {
                groups.computeIfAbsent(newKey(activity.getUserId(), activity.getActivityDate()), key -> new ArrayList<>())
                        .add(activity);
            }
        }

        int promoted = 0;
        int archived = 0;
        for (Map.Entry<String, List<HealthActivity>> group : groups.entrySet()) {
            String newKey = group.getKey();
            List<HealthActivity> legacyRows = group.getValue().stream().sorted(WINNER_FIRST).toList();
            boolean newKeyExists = activityRepository.existsByExternalKey(newKey);
            for (int i = 0; i < legacyRows.size(); i++) {
                HealthActivity row = legacyRows.get(i);
                if (i == 0 && !newKeyExists) {
                    activityRepository.updateExternalKey(row.getId(), newKey);
                    promoted++;
                } else {
                    activityRepository.updateExternalKey(row.getId(), row.getExternalKey() + LEGACY_MARKER + row.getId());
                    archived++;
                }
            }
        }
        if (promoted + archived > 0) {
            log.info("구 형식 수면 로그 키 정리: 새 키로 전환 {}건, legacy 보존 {}건", promoted, archived);
        }
    }

    static String newKey(String userId, LocalDate date) {
        return String.join("|", userId, date.toString(), "SLEEP");
    }
}
