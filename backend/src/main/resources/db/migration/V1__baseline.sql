-- #66 기준 스키마. Flyway 도입 전 JPA ddl-auto(update) 가 MySQL 8 에 만들던 것과 같다.
-- 이미 ddl-auto 로 만들어진 DB 는 baseline-on-migrate(baseline-version=1)로 이 파일을 건너뛴다.
-- 테스트(H2 MySQL 모드)에서도 그대로 실행되도록 테이블 옵션(engine 등) 없이 공통 문법만 쓴다.

create table user_character (
    id          binary(16)   not null,
    user_id     varchar(255) not null,
    level       integer      not null,
    current_xp  integer      not null,
    total_xp    integer      not null,
    str         integer,
    vit         integer,
    int_stat    integer,
    discipline  integer,
    recovery    integer,
    created_at  datetime(6)  not null,
    updated_at  datetime(6)  not null,
    primary key (id),
    constraint uk_character_user unique (user_id)
);

create table health_activity_log (
    id               binary(16)    not null,
    user_id          varchar(255)  not null,
    activity_date    date          not null,
    type             enum ('SLEEP','STEPS','WORKOUT') not null,
    source_type      varchar(255),
    duration_minutes integer,
    calories         decimal(38,2),
    distance_meters  decimal(38,2),
    steps            integer,
    sleep_minutes    integer,
    sleep_score      integer,
    gained_xp        integer       not null,
    external_key     varchar(512)  not null,
    started_at       datetime(6),
    ended_at         datetime(6),
    created_at       datetime(6)   not null,
    primary key (id),
    constraint uk_health_activity_external_key unique (external_key)
);

create index idx_health_activity_user_type_time on health_activity_log (user_id, type, started_at, ended_at);
create index idx_health_activity_user_date on health_activity_log (user_id, activity_date);

create table user_credential (
    id              binary(16)   not null,
    username        varchar(32)  not null,
    password_hash   varchar(100) not null,
    user_identifier varchar(64)  not null,
    display_name    varchar(64),
    created_at      datetime(6)  not null,
    updated_at      datetime(6)  not null,
    primary key (id),
    constraint uk_credential_username unique (username),
    constraint uk_credential_user_identifier unique (user_identifier)
);
