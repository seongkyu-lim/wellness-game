-- Auth v2(#55): 회전 리프레시 토큰. 원문은 저장하지 않고 SHA-256 hex(64자)만 둔다.
-- family_id 는 한 로그인에서 회전으로 이어지는 토큰 묶음이다. family_created_at 은 회전해도 넘지 못하는 절대 만료의 기준이다.
-- 유예 구간(auth.refresh.reuse-grace)이 지난 뒤 회전된(replaced_by 가 있는) 토큰이 다시 오면 family 전체를 폐기한다.
-- 테스트(H2 MySQL 모드)에서도 그대로 실행되도록 테이블 옵션(engine 등) 없이 공통 문법만 쓴다.

create table refresh_token (
    id                binary(16)   not null,
    user_id           varchar(255) not null,
    family_id         binary(16)   not null,
    family_created_at datetime(6)  not null,
    token_hash        varchar(64)  not null,
    issued_at         datetime(6)  not null,
    expires_at        datetime(6)  not null,
    revoked_at        datetime(6),
    replaced_by       binary(16),
    primary key (id),
    constraint uk_refresh_token_hash unique (token_hash)
);

create index idx_refresh_token_family on refresh_token (family_id);
create index idx_refresh_token_user on refresh_token (user_id);
create index idx_refresh_token_expires on refresh_token (expires_at);

-- 사용자별 액세스 토큰 무효화 기준. iat 가 tokens_valid_after 보다 이전인 액세스 토큰은 401 이다
-- (모든 기기 로그아웃, 리프레시 토큰 재사용 감지 때 갱신).
create table user_auth_state (
    user_id            varchar(255) not null,
    tokens_valid_after datetime(6)  not null,
    primary key (user_id)
);
