-- Auth v2(#55): 회전 리프레시 토큰. 원문은 저장하지 않고 SHA-256 hex(64자)만 둔다.
-- family_id 는 한 로그인에서 회전으로 이어지는 토큰 묶음이다. 회전된(revoked) 토큰이 다시 오면 family 전체를 폐기한다.
-- 테스트(H2 MySQL 모드)에서도 그대로 실행되도록 테이블 옵션(engine 등) 없이 공통 문법만 쓴다.

create table refresh_token (
    id          binary(16)   not null,
    user_id     varchar(255) not null,
    family_id   binary(16)   not null,
    token_hash  varchar(64)  not null,
    issued_at   datetime(6)  not null,
    expires_at  datetime(6)  not null,
    revoked_at  datetime(6),
    replaced_by binary(16),
    primary key (id),
    constraint uk_refresh_token_hash unique (token_hash)
);

create index idx_refresh_token_family on refresh_token (family_id);
create index idx_refresh_token_user on refresh_token (user_id);
create index idx_refresh_token_expires on refresh_token (expires_at);
