package com.wellnessgame.activity;

/**
 * 활동 유형. {@code health_activity_log.type} 에 이름 문자열로 저장된다.
 *
 * <p>값을 추가·변경하면 새 Flyway 마이그레이션으로 {@code ck_health_activity_type} check 제약도 갱신해야 한다
 * (ddl-auto=validate 는 check 제약을 보지 않아 기동은 되지만 운영 INSERT 가 23514 로 실패한다).
 * {@code EnumCheckConstraintTest} 가 enum 값과 마이그레이션의 check 목록이 같은지 확인한다.
 */
public enum ActivityType {
    STEPS,
    WORKOUT,
    SLEEP
}
