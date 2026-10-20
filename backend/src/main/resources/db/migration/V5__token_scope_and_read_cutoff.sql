-- #103·#105: 리프레시 토큰 family 의 권한 범위, 유예 재발급 1회 제한, 조회 전용 액세스 토큰 무효화 기준.
-- PostgreSQL 과 테스트(H2 PostgreSQL 모드)에서 그대로 실행되도록 공통 문법만 쓴다. 타입 매핑은 V1 주석 참고.
-- 컬럼 이름·값은 다른 서버 구현과 공유하므로 바꾸지 않는다.

-- scope: 이 family 가 회전으로 다시 발급하는 액세스 토큰 범위. 'full'(앱, 기존 행) 또는 'read'(웹 코드 교환 로그인).
-- grace_reissued: 이 토큰이 회전된 뒤 유예 구간 재발급을 이미 한 번 했는지. 두 번째는 재사용 공격으로 보고 family 를 폐기한다.
alter table refresh_token add column scope varchar(16) default 'full' not null;
alter table refresh_token add column grace_reissued boolean default false not null;
alter table refresh_token add constraint ck_refresh_token_scope check (scope in ('full', 'read'));

-- 조회 전용(scope=read) 액세스 토큰만의 무효화 기준. 이 기기 로그아웃(/api/auth/logout) 때 지금 시각으로 옮긴다.
-- null 이면 기준 없음. tokens_valid_after(모든 범위)와 함께 더 늦은 쪽을 적용한다.
alter table user_auth_state add column read_tokens_valid_after timestamp(6) with time zone;
