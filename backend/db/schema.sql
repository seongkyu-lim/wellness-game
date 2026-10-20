-- 최신 전체 DB 스키마(PostgreSQL, #98). Flyway 마이그레이션 V1~V6 을 모두 적용한 최종 상태를 한 파일로 모은 것이다.
--
-- - 자동 실행되지 않는다. 리소스 경로(src/main/resources) 밖에 두어 Flyway·애플리케이션이 읽지 않는다.
--   실제 스키마는 언제나 src/main/resources/db/migration 의 마이그레이션이 만든다. 이 파일은 구조 참고·수동 세팅용이다.
-- - 마이그레이션을 추가하면 이 파일도 함께 고친다. 일치 여부는 postgresTest(SchemaSqlMatchesMigrationsPostgresTest)가
--   빈 DB 두 개(Flyway migrate / 이 파일 실행)의 테이블·컬럼·제약·인덱스·RLS 를 비교해 검사한다.
-- - flyway_schema_history 는 넣지 않는다(Flyway 가 관리). 그래서 이 파일로 만든 DB 에 서버를 붙이면
--   "non-empty schema" 로 기동에 실패한다. 붙이려면 먼저 Flyway baseline 을 현재 최신 버전으로 잡아야 한다
--   (예: flyway -baselineVersion=6 baseline, 마이그레이션 추가 뒤에는 그 버전).
-- - 데이터 마이그레이션(V2 수면 키 정리)은 스키마를 바꾸지 않으므로 여기에 없다.
-- - 타입 매핑은 V1 주석 참고: UUID → uuid, Instant → timestamp(6) with time zone, BigDecimal → numeric(38,2),
--   @Enumerated(STRING) → varchar(255) + check 제약.

-- V1 ----------------------------------------------------------------------------------------------

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

-- V3 (Auth v2 리프레시 토큰) ----------------------------------------------------------------------

create table refresh_token (
    id                uuid                        not null,
    user_id           varchar(255)                not null,
    family_id         uuid                        not null,
    family_created_at timestamp(6) with time zone not null,
    token_hash        varchar(64)                 not null,
    issued_at         timestamp(6) with time zone not null,
    expires_at        timestamp(6) with time zone not null,
    revoked_at        timestamp(6) with time zone,
    replaced_by       uuid,
    -- V5(#103·#105): family 권한 범위('full' 앱 / 'read' 웹), 유예 재발급 1회 제한
    scope             varchar(16)                 default 'full' not null,
    grace_reissued    boolean                     default false not null,
    constraint refresh_token_pkey primary key (id),
    constraint uk_refresh_token_hash unique (token_hash),
    constraint ck_refresh_token_scope check (scope in ('full', 'read'))
);

create index idx_refresh_token_family on refresh_token (family_id);
create index idx_refresh_token_user on refresh_token (user_id);
create index idx_refresh_token_expires on refresh_token (expires_at);

create table user_auth_state (
    user_id                 varchar(255)                not null,
    tokens_valid_after      timestamp(6) with time zone not null,
    -- V5(#103): 조회 전용(scope=read) 액세스 토큰만의 무효화 기준(이 기기 로그아웃). null 이면 없음.
    read_tokens_valid_after timestamp(6) with time zone,
    constraint user_auth_state_pkey primary key (user_id)
);

-- V4 (모든 테이블 RLS, 허용 정책 없음) ---------------------------------------------------------------
-- 백엔드는 테이블 소유자로 접속해 RLS 를 적용받지 않는다(FORCE 미사용). Supabase 데이터 API(anon 키) 경로만 막는다.

alter table health_activity_log enable row level security;
alter table refresh_token enable row level security;
alter table user_auth_state enable row level security;
alter table user_character enable row level security;
alter table user_credential enable row level security;

-- V6 (Supabase 데이터 API 역할 권한 회수, #104) ----------------------------------------------------------
-- anon·authenticated 역할이 있을 때만(Supabase). flyway_schema_history 는 이 파일에 없으므로 대상이 아니다.
-- 권한은 postgresTest 의 스키마 비교 대상이 아니다(V6 자체는 PostgresIntegrationTest 가 검사).

do $$
declare
    roles text;
begin
    select string_agg(quote_ident(rolname), ', ' order by rolname) into roles
    from pg_roles where rolname in ('anon', 'authenticated');
    if roles is null then
        return;
    end if;
    execute format('revoke all on all tables in schema %I from %s', current_schema(), roles);
    execute format('revoke all on all sequences in schema %I from %s', current_schema(), roles);
    execute format('revoke all on all functions in schema %I from %s', current_schema(), roles);
    execute format('alter default privileges in schema %I revoke all on tables from %s', current_schema(), roles);
    execute format('alter default privileges in schema %I revoke all on sequences from %s', current_schema(), roles);
    execute format('alter default privileges in schema %I revoke all on functions from %s', current_schema(), roles);
end
$$;
