# Wellness Game API — Hono 서버 (#110)

`backend/`(Spring Boot)와 **같은 API 계약·같은 PostgreSQL 스키마·같은 JWT 형식**을 TypeScript(Hono)로 구현한 경량 서버다.
클라이언트(web·iOS·Android·Watch)는 바꾸지 않고 API 주소만 바꿔 어느 서버든 쓸 수 있다. Spring 서버는 그대로 유지한다.

- 런타임: **Cloudflare Workers**(+ Hyperdrive → Supabase Postgres) 또는 **Node 22**(Render·로컬)
- 같은 DB 를 두 서버가 함께 써도 된다(토큰·리프레시 토큰·활동 기록 형식이 같다). 스키마는 언제나 Spring Flyway 가 만든다.

## 구조

```
hono-server/
  src/
    index.ts            Workers 진입점(fetch + scheduled: 리프레시 토큰 정리 Cron)
    node.ts             Node 진입점(@hono/node-server, 매일 04:00 정리, graceful shutdown)
    app.ts              미들웨어 순서·라우트·오류 응답(Spring ApiExceptionHandler 형식)
    config.ts           환경변수(이름·기본값 = application.yml)
    services.ts         서비스 묶음(인메모리 상태 포함, 프로세스/isolate 당 1개)
    http/               보안 헤더·CORS·레이트 리밋·인증(READ 허용 목록)·클라이언트 IP·본문 역직렬화/검증
    auth/               JWT, 리프레시 회전, 토큰 무효화 기준, 비밀번호(BCrypt)·잠금, 소셜(웹 코드 교환·카카오·네이버·구글·애플), JWKS 캐시
    activity/           동기화 검증·XP 규칙·동기화 서비스(#107 반영)·SQL
    character/          레벨·스탯·SQL
    routes/             auth / characters·health-activities / OpenAPI 문서
    lib/                Instant(java.time 호환 문자열), 날짜·시간대, i18n(messages*.properties 이식), 오류
  test/unit, test/api   Vitest(API 테스트는 DATABASE_URL_TEST 가 있을 때만)
```

## 구현 범위

| 경로 | 내용 |
|---|---|
| `POST /api/auth/signup`, `/login` | 아이디·비밀번호(BCrypt cost 10, Spring 해시와 호환), 잠금((아이디,IP) 5회·아이디 50회·15분) |
| `POST /api/auth/{google,kakao,naver,apple}/native` | 구글·애플 ID 토큰 JWKS(RS256) 검증, 카카오 `access_token_info`+프로필, 네이버 refresh 갱신으로 앱 소유 확인 |
| `POST /api/auth/{kakao,naver,google}` | 웹 코드 교환. **조회 전용(read) 토큰 + read family**, 구글·카카오는 `codeVerifier` 필수(PKCE) |
| `POST /api/auth/refresh` | 회전, family 범위 유지, 유예 30초·토큰당 1회, 재사용 감지(family 폐기 + 액세스 토큰 무효화), 절대 수명 |
| `POST /api/auth/logout` | family 폐기. full family 면 그 사용자의 조회 전용 토큰도 무효화 |
| `POST /api/auth/logout-all`, `/watch-token` | 전체 무효화 / Watch 조회 토큰(scope=read, 7일) |
| `GET /api/characters/me`, `/{userId}` | 캐릭터(없으면 빈 404) |
| `POST /api/health-activities/sync`, `GET /api/health-activities` | XP·레벨·스탯·일일 상한·중복/겹침 처리, 날짜별 조회 |
| `GET /healthz` | `{"status":"UP"}`, DB 미확인 |
| `/v3/api-docs`, `/swagger-ui/index.html` | `APP_API_DOCS_ENABLED=true` 일 때만(기본 꺼짐) |

공통: 오류 본문 `{timestamp,status,error,message}`(404·405·415 는 스프링 기본 `{timestamp,status,error,path}`), 문구는
`Accept-Language` ko/en(Spring `AcceptHeaderLocaleResolver` 와 같은 선택 규칙), Bean Validation 과 같은 `필드: 문구`,
형식 오류는 `error.unreadable-body`, 401 에 `WWW-Authenticate: Bearer`, 429 에 `Retry-After`.
보안 헤더(HSTS·nosniff·X-Frame-Options DENY·CSP frame-ancestors 'none'·Referrer-Policy no-referrer), CORS 허용 목록.

## 실행

```bash
cd hono-server
npm ci

# Node(로컬 DB: 저장소 루트 docker-compose 의 PostgreSQL 등)
cp .env.example .env
npm run dev:node            # http://localhost:8080
npm run build && npm start  # 운영(Render 등)

# Workers(로컬 workerd)
cp .dev.vars.example .dev.vars
npm run dev                 # wrangler dev, DB 는 wrangler.toml 의 localConnectionString

npm run typecheck
npm test                    # 단위 테스트. DB 테스트까지: DATABASE_URL_TEST=postgres://... npm test
npm run deploy              # wrangler deploy(사람이 확인 후 실행. Hyperdrive id·secret 먼저 설정)
```

DB 테스트는 `DATABASE_URL_TEST` 의 **public 스키마를 지우고** `backend/db/schema.sql` 로
다시 만든다. 전용 DB 를 쓴다. 예: `docker run --rm -d -p 55432:5432 -e POSTGRES_USER=wellness -e POSTGRES_PASSWORD=wellness -e POSTGRES_DB=wellness_test postgres:16-alpine`

### Workers 배포 준비(실행은 별도 확인)

1. `wrangler hyperdrive create wellness-db --connection-string="postgres://...supabase...:5432/postgres?sslmode=require"` → `wrangler.toml` 의 `id` 교체
2. `wrangler secret put AUTH_JWT_SECRET`(Spring 과 같은 값이면 토큰 공유), OAuth 비밀값도 secret 으로
3. `[vars]` 의 `CORS_ALLOWED_ORIGINS` 를 운영 웹 주소로

## 런타임별 주의

- **BCrypt CPU**: 해시 한 번이 수십 ms CPU 다. Workers **무료 플랜(요청당 CPU 10ms)** 을 넘으므로 아이디 가입·로그인은
  **Workers 유료(Standard) 플랜이나 Node 런타임**에서 운영한다. 소셜 로그인·토큰 갱신·동기화는 무료 한도 안에서 동작한다.
- **인메모리 상태**(레이트 리밋 카운터, 로그인 잠금, 토큰 무효화 기준 30초 캐시, JWKS 캐시)는 Spring 처럼 인스턴스별이다.
  Workers 는 isolate 마다 따로라 레이트 리밋·잠금이 느슨해질 수 있다(실제 한도 ≈ isolate 수 × 한도). 공유가 필요하면
  `RateLimitStore` 인터페이스(`src/http/rate-limit.ts`)를 KV·Durable Object·Redis 로 구현해 `createServices({ rateLimitStore })` 로 넣는다.
  다른 인스턴스의 로그아웃 반영은 최대 30초 늦다(Spring 다중 인스턴스와 같음).
- **클라이언트 IP**: Workers 는 `CF-Connecting-IP`. Node 는 기본 소켓 주소, `APP_RATE_LIMIT_TRUST_FORWARDED_HEADER=true` 면
  `X-Forwarded-For` 에서 오른쪽부터 `APP_RATE_LIMIT_TRUSTED_PROXY_HOPS` 개를 건너뛴 값(0 = 마지막 값). 헤더가 여러 줄이면
  위조로 보고 고정 공용 키 하나로 센다. IPv6 는 /64 로 묶는다.
- **정리 작업**: Workers 는 Cron Trigger(매일 19:00 UTC = 04:00 KST), Node 는 매일 04:00(APP_TIMEZONE). `AUTH_REFRESH_CLEANUP_CRON` 은 해석하지 않는다.

## 환경변수

Spring `application.yml` 과 같은 이름·기본값: `DATABASE_URL`(jdbc: 형식도 됨)·`DATABASE_USERNAME`·`DATABASE_PASSWORD`·`DB_POOL_MAX`,
`AUTH_JWT_SECRET`·`AUTH_JWT_TTL`(1h)·`AUTH_WATCH_TOKEN_TTL`(7d)·`AUTH_JWT_ISSUER`·`AUTH_JWT_AUDIENCE`·`AUTH_JWT_LEGACY_CUTOFF`,
`AUTH_REFRESH_TTL`(30d)·`AUTH_REFRESH_ABSOLUTE_TTL`(90d)·`AUTH_REFRESH_REUSE_GRACE`(30s)·`AUTH_REFRESH_REVOKED_RETENTION`(7d),
`APP_TIMEZONE`·`APP_API_DOCS_ENABLED`·`CORS_ALLOWED_ORIGINS`, `APP_RATE_LIMIT_{SYNC,AUTH,LOGIN}_PER_MINUTE`·`..._TRUST_FORWARDED_HEADER`·`..._MAX_KEYS`,
`APP_AUTH_MAX_FAILED_LOGINS`·`..._PER_USERNAME`·`APP_AUTH_LOCKOUT_MINUTES`, `OAUTH_{KAKAO,NAVER,GOOGLE}_CLIENT_ID/SECRET`,
`OAUTH_KAKAO_NATIVE_APP_ID`, `OAUTH_APPLE_BUNDLE_ID`·`OAUTH_APPLE_REQUIRE_NONCE`, `PORT`(Node).

Spring(#102~#106)과 맞춘 값: `AUTH_REFRESH_READ_TTL`(7d)·`AUTH_REFRESH_READ_ABSOLUTE_TTL`(30d),
`APP_RATE_LIMIT_REFRESH_PER_MINUTE`(120)·`APP_RATE_LIMIT_TRUSTED_PROXY_HOPS`(0), `OAUTH_GOOGLE_NATIVE_CLIENT_IDS`(없으면 `OAUTH_GOOGLE_IOS_CLIENT_ID`).

Hono 에만 있는 값: `APP_RATE_LIMIT_GENERAL_PER_MINUTE`(0=끔, /api 전체 IP 별), `APP_HTTP_CLIENT_TIMEOUT`(8s, 외부 인증 서버),
`APP_HSTS_HEADER`(빈 값이면 HSTS 생략), `OAUTH_{GOOGLE,APPLE}_JWKS_CACHE_TTL`·`..._JWKS_STALE_MAX`, `DATABASE_URL_TEST`(테스트).

## Spring과 맞춰야 할 스키마

스키마는 Spring Flyway 가 관리하고 Hono 는 마이그레이션을 하지 않는다(`backend/db/schema.sql` = 최신). 아래는 Spring V5(#103·#105)에서 추가된,
Hono 가 같은 의미로 쓰는 컬럼이다. 스키마가 바뀌면 두 서버를 함께 고친다.

| 테이블.컬럼 | 정의 | 쓰임 |
|---|---|---|
| `refresh_token.scope` | `varchar(16) default 'full' not null`, check `('full','read')` | family 범위. 회전 후에도 유지. read = 웹 코드 교환 |
| `refresh_token.grace_reissued` | `boolean default false not null` | 회전된 토큰당 유예 재발급 1회 |
| `user_auth_state.read_tokens_valid_after` | `timestamptz null` | 조회 전용 토큰 무효화 기준(full family 로그아웃) |

`user_auth_state` 행이 없을 때 조회 기준만 옮기면 `tokens_valid_after` 에 `1970-01-01T00:00:00Z`(Instant.EPOCH)를 넣고 "기준 없음"으로 읽는다.

## Spring 과 의도적으로 다르거나 확인이 필요한 점

- 규칙 기준: dev 에 머지된 Spring 코드(#102~#107, PR #114·#115·#116). 문구는 `messages*.properties` 를 그대로 옮겼다.
- 웹 코드 교환에서 구글·카카오 `codeVerifier` 누락은 Spring 처럼 공급자 설정 확인(503) 뒤 400 `auth.social.code-verifier-required`.
- 토큰 무효화 기준 캐시는 Spring 처럼 트랜잭션 커밋 뒤에 반영한다.
- 웹 read 액세스 토큰 유효 기간은 `AUTH_JWT_TTL`(Watch 토큰만 7일).
- JSON 숫자: numeric(38,2) 컬럼(calories·distanceMeters)을 Spring 은 `300.00`, Hono 는 `300` 으로 쓴다(값은 같음).
- Bean Validation 위반이 여러 개면 Spring 은 순서가 정해져 있지 않다. Hono 는 필드 선언 순서의 첫 위반을 쓴다.
- 404·405·415 본문은 스프링 기본 형식을 흉내 낸 것이다(`path` 포함). OPTIONS(비 preflight) 자동 응답은 없다.
- 72바이트 넘는 비밀번호로 로그인하면 Hono 는 일반 실패(400 invalid-credentials)다(가입은 검증 문구로 400).
- 정리 작업 cron 표현식은 해석하지 않고 매일 04:00 고정.
