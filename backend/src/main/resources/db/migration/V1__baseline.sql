-- #66 기준 스키마(#87 에서 PostgreSQL 로 다시 씀). 운영(Supabase PostgreSQL)의 빈 DB 에 V1 부터 차례로 실행된다.
-- 컬럼 타입은 Hibernate 의 PostgreSQL 기본 매핑과 같다(ddl-auto=validate):
--   UUID → uuid, Instant → timestamp(6) with time zone(timestamptz), BigDecimal → numeric(38,2),
--   @Enumerated(STRING) → varchar(255) + check 제약.
-- 테스트(H2 PostgreSQL 모드)에서도 그대로 실행되도록 PostgreSQL 전용 문법(확장, 도메인 타입 등)은 쓰지 않는다.

create table user_character (
    id          uuid                        not null,
    user_id     varchar(255)                not null,
    level       integer                     not null,
    current_xp  integer                     not null,
    total_xp    integer                     not null,
    str         integer,
    vit         integer,
    int_stat    integer,
    discipline  integer,
    recovery    integer,
    created_at  timestamp(6) with time zone not null,
    updated_at  timestamp(6) with time zone not null,
    constraint user_character_pkey primary key (id),
    constraint uk_character_user unique (user_id)
);

create table health_activity_log (
    id               uuid                        not null,
    user_id          varchar(255)                not null,
    activity_date    date                        not null,
    type             varchar(255)                not null,
    source_type      varchar(255),
    duration_minutes integer,
    calories         numeric(38,2),
    distance_meters  numeric(38,2),
    steps            integer,
    sleep_minutes    integer,
    sleep_score      integer,
    gained_xp        integer                     not null,
    external_key     varchar(512)                not null,
    started_at       timestamp(6) with time zone,
    ended_at         timestamp(6) with time zone,
    created_at       timestamp(6) with time zone not null,
    constraint health_activity_log_pkey primary key (id),
    constraint uk_health_activity_external_key unique (external_key),
    constraint ck_health_activity_type check (type in ('SLEEP', 'STEPS', 'WORKOUT'))
);

create index idx_health_activity_user_type_time on health_activity_log (user_id, type, started_at, ended_at);
create index idx_health_activity_user_date on health_activity_log (user_id, activity_date);

create table user_credential (
    id              uuid                        not null,
    username        varchar(32)                 not null,
    password_hash   varchar(100)                not null,
    user_identifier varchar(64)                 not null,
    display_name    varchar(64),
    created_at      timestamp(6) with time zone not null,
    updated_at      timestamp(6) with time zone not null,
    constraint user_credential_pkey primary key (id),
    constraint uk_credential_username unique (username),
    constraint uk_credential_user_identifier unique (user_identifier)
);
