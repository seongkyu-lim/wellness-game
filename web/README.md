# Wellness Game Web

iOS 앱과 동일한 백엔드·동일한 캐릭터를 사용하는 **조회 전용** 웹 대시보드입니다.
데이터 수집은 iPhone 앱이 HealthKit에서 자동으로 처리하고, 웹은 서버에 쌓인 결과(캐릭터·활동·XP)를 보여줍니다.

## 실행

```bash
# 1. 백엔드 실행 (프로젝트 루트에서)
docker compose up --build   # http://localhost:8080

# 2. 웹 개발 서버
cd web
npm install
npm run dev                 # http://localhost:5173
```

백엔드 주소가 다르면 `VITE_API_BASE_URL` 환경변수로 지정합니다.

```bash
VITE_API_BASE_URL=http://192.168.0.10:8080 npm run dev
```

## 앱과의 연동 구조

- 역할 분담: **iOS 앱 = HealthKit 자동 수집·동기화(쓰기), 웹 = 대시보드(읽기)**
  - 앱: `POST /api/health-activities/sync`
  - 웹: `GET /api/characters/me`, `GET /api/health-activities?userId=&date=`
  - 웹은 30초마다 자동 새로고침하므로 앱에서 동기화하면 곧바로 반영됩니다.
- **웹은 로그인한 사용자만 이용할 수 있으며, 본인 데이터만 조회합니다.** 게스트 모드와 사용자 ID 직접 입력 기능은 없습니다.
  - 사용자 식별자: `{provider}:{식별자}` (예: `kakao:12345`)
  - 모든 조회 API는 서버가 발급한 토큰을 `Authorization: Bearer <accessToken>` 헤더로 보냅니다.
    서버는 `userId` 쿼리를 토큰 주체와 대조하며, 토큰이 없거나 무효면 401, 다른 사용자 데이터면 403을 반환합니다.
  - 401을 받으면 웹은 리프레시 토큰으로 한 번 갱신해 원 요청을 1회 재시도하고, 갱신이 거부되면 세션을 지우고
    "로그인 만료" 안내와 함께 로그인 화면으로 돌아갑니다(아래 [토큰 갱신](#토큰-갱신-auth-v2) 참고).
- 캐릭터 상태는 서버(DB)에만 존재하므로, **같은 계정으로 로그인하면 앱과 웹이 같은 캐릭터를 공유**합니다.
- 프로덕션 배포 시 백엔드 `CORS_ALLOWED_ORIGINS` 환경변수에 웹 오리진을 추가해야 합니다.

## 소셜 로그인 설정 (앱-웹 자동 연동)

웹에서 iPhone 앱과 같은 계정(구글/카카오/네이버)으로 로그인하면 같은 `{provider}:{식별자}` userId가 만들어져
캐릭터가 자동으로 연동됩니다.

- **반드시 iOS 앱과 같은 provider 애플리케이션**에 웹 플랫폼을 추가해야 동일한 식별자가 발급됩니다.
- 각 provider 콘솔에 Redirect URI `http://localhost:5173/` (배포 시 실제 도메인)을 등록하세요.
- 키가 없으면 해당 로그인 버튼이 자동으로 비활성화됩니다.

| Provider | 웹 `.env` | 백엔드 환경변수 |
|---|---|---|
| Kakao | `VITE_KAKAO_CLIENT_ID` (REST API 키) | `OAUTH_KAKAO_CLIENT_ID`, `OAUTH_KAKAO_CLIENT_SECRET`(선택) |
| Naver | `VITE_NAVER_CLIENT_ID` | `OAUTH_NAVER_CLIENT_ID`, `OAUTH_NAVER_CLIENT_SECRET` |
| Google | `VITE_GOOGLE_CLIENT_ID` | `OAUTH_GOOGLE_CLIENT_ID`, `OAUTH_GOOGLE_CLIENT_SECRET` |

인증 흐름: 웹 → provider 로그인 → code와 함께 리다이렉트 → `POST /api/auth/{provider}` →
백엔드가 토큰 교환·프로필 조회(client secret은 서버에만 보관) → `userId`, `displayName`,
`accessToken`(Bearer, `expiresIn` 초 유효, 기본 1시간), `refreshToken`(`refreshExpiresIn` 초 유효, 기본 30일) 반환 →
세션 저장. 토큰이 없는 예전 형식의 세션이나 만료된 세션은 로그아웃 상태로 처리됩니다.

### 토큰 갱신 (Auth v2)

서버 계약은 루트 README의 "인증 세션 (Auth v2)" 절을 따릅니다. 구현은 `src/lib/session.ts`(`SessionManager`)와
`src/api/client.ts`(`authorizedFetch`)에 있습니다.

| 값 | 저장 위치 | 비고 |
|---|---|---|
| 프로필(`provider`·`userId`·`displayName`), `refreshToken`, 리프레시 만료 시각 | localStorage `wellness.session` | 탭 간 공유·재방문 유지 |
| `accessToken`, 액세스 만료 시각 | 메모리(원본) + sessionStorage `wellness.access`(새로고침 대비 사본) | 탭마다 따로. 새 탭은 첫 요청 전에 갱신 |

- **사전 갱신:** 액세스 토큰이 없거나 만료 60초 이내면 요청 전에 `POST /api/auth/refresh`로 회전합니다.
- **401 처리:** 보호 API가 401이면 refresh 후 새 토큰으로 원 요청을 **1회만** 재시도합니다. refresh가 401·400이면 세션을 지우고
  "로그인 만료" 배너를 띄웁니다. 네트워크 오류면 세션은 유지하고 일반 오류 배너를 띄웁니다(다음 자동 새로고침에서 다시 시도).
- **직렬화:** 서버는 이미 회전된 토큰이 다시 오면 family 전체를 폐기하므로 같은 리프레시 토큰을 두 번 보내면 안 됩니다.
  - 탭 안: 진행 중인 refresh Promise를 공유해 동시 401이 여러 개여도 refresh는 한 번입니다.
  - 탭 사이: `BroadcastChannel('wellness.auth')`로 `refresh-start`(선점, 50ms 동안 겹치면 tabId가 작은 탭이 이김)·`refreshed`
    (새 토큰 전달)·`refresh-end`(포기·네트워크 실패)·`signed-out`을 주고받습니다. 다른 탭이 갱신 중이면 최대 10초 기다려 그 결과를
    반영하고, refresh 직전에는 localStorage의 최신 리프레시 토큰을 다시 읽어 이미 폐기된 토큰을 보내지 않습니다.
  - 한 탭에서 로그아웃하거나 세션이 만료되면 다른 탭도 로그인 화면으로 돌아갑니다(만료면 배너 표시).
- **로그아웃:** 로컬 세션을 즉시 지우고 `POST /api/auth/logout`(무인증, body `{refreshToken}`만, Authorization 없음)으로
  이 기기 family를 폐기합니다. 액세스 토큰이 만료돼도 갱신 없이 바로 보냅니다. 서버 요청이 실패해도 로컬 로그아웃은 유지됩니다.
- **서버 유예 구간:** 서버는 방금 회전된 리프레시 토큰이 30초 안에 다시 오면 family를 폐기하지 않고 정상 응답합니다.
  새 탭과 진행 중 갱신의 경합은 이것으로도 완화되지만, 불필요한 회전을 줄이도록 위 탭 간 조율은 유지합니다.
- **이전 세션:** 리프레시 토큰이 없는 예전 형식 세션(localStorage에 `accessToken`·`expiresAt`)은 액세스 토큰이 유효한 동안
  그대로 쓰고, 401이나 만료가 되면 갱신 없이 로그아웃합니다. 서버가 리프레시 토큰을 주지 않는 응답도 같은 형식으로 저장합니다.

Apple 로그인은 Apple Developer 유료 계정 + HTTPS 도메인 + Services ID 등록이 선행돼야 해서 웹에서는 아직 지원하지 않습니다.

## 다국어 (ko / en)

- UI는 한국어(ko)와 영어(en)를 지원합니다. 외부 라이브러리 없이 `src/i18n/`의 사전(`dictionaries.ts`)과
  `useI18n` 훅·`I18nProvider`로 구현했고, 날짜·숫자 포맷은 `Intl`을 사용합니다.
- 언어 결정 순서: localStorage(`wellness.lang`) 저장값 → `navigator.language`(ko로 시작하면 ko, 아니면 en).
  헤더 우측의 한/EN 토글로 수동 전환하며, 선택은 저장되고 `<html lang>`에 반영됩니다.
- 모든 API 요청에 `Accept-Language: ko|en`을 보내며, 서버 오류 메시지는 서버가 해당 언어로 번역합니다.
- 에러·안내 배너는 번역된 문자열이 아니라 `{ key, params } | { raw }`(`Message`)로 보관하고 렌더링할 때 번역하므로,
  배너가 떠 있는 동안 언어를 바꿔도 새 언어로 다시 그려집니다. 서버 원문(`raw`)은 다음 자동 새로고침 때 새 언어로 갱신되지만,
  로그아웃 상태(예: 로그인 교환 실패)의 서버 원문 배너는 새로고침이 없어 이전 언어로 남습니다.
- 언어 전환은 데이터를 다시 조회하지 않습니다(조회 응답에 서버 번역 문구가 없음, `App.tsx` 주석 참고).
- 문구를 추가할 때는 ko·en 사전에 같은 키를 함께 추가하세요(누락하면 타입 체크가 실패합니다).
  영어 용어(캐릭터·단계·스탯)는 [GLOSSARY.md](GLOSSARY.md)를 따릅니다.

## 빌드·테스트

```bash
npm run build   # 타입 체크 + dist/ 생성 (CSP meta 주입)
npm test        # Vitest(jsdom) 단위·렌더링 테스트
```

테스트는 `src/**/*.test.ts(x)`, `csp.test.ts`, `build.test.ts`(실제 프로덕션 빌드의 `index.html`에 CSP meta가 폰트·스크립트보다 앞에 오는지)에 있습니다. i18n(언어 감지·번역·`Intl` 포맷·배너 재번역),
API 클라이언트 헤더·401 처리·토큰 갱신·로그아웃, 세션 로드·만료(`lib/auth.ts`), 탭 간 갱신 조율(`lib/session.test.ts`, 가짜
BroadcastChannel), 로그인 버튼 배치, CSP 생성을 검사합니다.
CI(`.github/workflows/web-ci.yml`)는 PR·push마다 `npm test`와 `npm run build`를 실행합니다.

## 보안: CSP와 토큰 저장

### Content-Security-Policy

`npm run build` 시 `csp.ts`의 Vite 플러그인(`transformIndexHtml`, `apply: 'build'`)이 `dist/index.html` 맨 앞에
CSP meta를 주입합니다. 개발 서버는 HMR·React Refresh가 인라인 스크립트와 웹소켓을 쓰므로 적용하지 않습니다.

```
default-src 'self'; script-src 'self'; style-src 'self' https://fonts.googleapis.com;
font-src https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self' <VITE_API_BASE_URL의 오리진>;
object-src 'none'; base-uri 'self'; form-action 'self'
```

- `connect-src`는 빌드 시점의 `VITE_API_BASE_URL` 오리진으로 정해집니다. API 주소를 바꾸면 다시 빌드해야 합니다.
  프로덕션 빌드에서 값이 비어 있으면 localhost를 넣지 않고 `'self'`만 허용하며 경고를 출력합니다(API 호출 차단).
  Vercel 빌드(`VERCEL=1`)에서는 경고 대신 빌드를 실패시킵니다.
- 소셜 로그인은 provider 페이지로의 최상위 이동이고 토큰 교환은 백엔드가 하므로 `connect-src`에 provider 도메인이 없습니다.
- 외부 리소스(스크립트·이미지·폰트 CDN 등)를 추가하면 `csp.ts`의 지시문도 함께 고치세요.
- **`frame-ancestors`(클릭재킹 방지)는 meta로 지정할 수 없어** Vercel 응답 헤더(`vercel.json`)로
  `Content-Security-Policy: frame-ancestors 'none'`(구형 브라우저용 `X-Frame-Options: DENY` 병행)을 보냅니다.
  헤더 CSP와 meta CSP는 둘 다 적용(교집합)되는데, 헤더에는 `frame-ancestors`만 있어 meta 정책을 더 좁히거나 충돌하지 않습니다.
  전체 정책을 헤더로 옮기지 않는 이유는 `connect-src`가 빌드 시점 `VITE_API_BASE_URL`에 따라 정해지기 때문입니다(빌드 산출물 하나에 정책이 같이 묶임).

### 웹 토큰 저장 위험과 완화

리프레시 토큰은 `localStorage`(`wellness.session`)에, 액세스 토큰은 메모리와 `sessionStorage`(`wellness.access`)에 두므로,
페이지에서 임의 스크립트가 실행되면(XSS) **여전히 두 토큰 모두 탈취될 수 있습니다.** Web Storage는 스크립트에서 읽을 수 있고,
메모리에만 두더라도 같은 페이지의 스크립트는 API 호출을 가로챌 수 있습니다. Auth v2로 위험은 다음처럼 줄었습니다.

- 액세스 토큰 유효 기간이 30일에서 **1시간**(`AUTH_JWT_TTL`)으로 짧아져, 액세스 토큰만 유출되면 피해 시간이 그만큼 제한됩니다.
- 리프레시 토큰은 쓸 때마다 **회전**하고, 서버가 **재사용을 감지**하면 그 family 전체를 폐기합니다. 공격자와 정상 탭이 같은 토큰을
  쓰게 되면 둘 중 늦게 쓴 쪽에서 재사용이 감지돼 세션이 끊기므로, 탈취가 장기간 조용히 이어지기 어렵습니다(정상 사용자는 다시 로그인).
  다만 사용자가 웹을 다시 열지 않는 동안 공격자가 먼저 회전하면 리프레시 만료(기본 30일)까지 쓸 수 있습니다.
- 로그아웃하면 서버에서 이 기기 family를 폐기합니다. 이미 발급된 액세스 토큰은 만료(최대 1시간)까지 유효합니다.

그 밖의 완화책:

- CSP로 인라인·외부 스크립트 실행을 막고(`script-src 'self'`), 데이터 전송 대상을 API 오리진으로 제한합니다(`connect-src`).
- React 기본 이스케이프만 사용하고 `dangerouslySetInnerHTML`을 쓰지 않습니다. 서버 메시지도 텍스트로만 표시합니다.
- 웹은 조회 전용이라 토큰이 유출돼도 웹 화면으로 기록을 만들 수 없지만, 토큰 자체는 동기화 API도 호출할 수 있습니다.
  만료된 세션은 로드 시 지워지고, 갱신이 거부되면 즉시 로그아웃합니다.
- 공용 PC에서는 사용 후 로그아웃하도록 안내합니다(로그아웃 시 저장된 세션 삭제).
- 장기적으로는 HttpOnly·Secure·SameSite 쿠키 기반 세션(같은 사이트 배포 + CSRF 대책)으로 옮기는 것이 가장 안전합니다.

## Vercel 배포

운영 웹은 Vercel이 저장소를 직접 빌드해 배포합니다(전체 순서는 루트 README의 "운영 배포"). GitHub Actions(`web-ci.yml`)는
PR·push에서 테스트·빌드 검증만 합니다.

- Vercel 프로젝트의 **Root Directory를 `web`** 으로 지정합니다. 빌드 설정은 `vercel.json`(설치 `npm ci`, 빌드 `npm run build`, 출력 `dist`)에 있습니다.
- 환경변수(Production·Preview 모두): `VITE_API_BASE_URL`(Render 백엔드 주소, 필수), `VITE_GOOGLE_CLIENT_ID`, `VITE_KAKAO_CLIENT_ID`,
  `VITE_NAVER_CLIENT_ID`. 모두 번들에 들어가는 공개 값입니다. Vercel 빌드(`VERCEL=1`)에서 `VITE_API_BASE_URL`이 비어 있으면
  `csp.ts`가 빌드를 실패시킵니다(API 호출이 막힌 사이트가 배포되는 것 방지). 값을 바꾸면 다시 배포해야 반영됩니다.
- SPA 라우팅: `/assets/` 밖의 모든 경로를 `index.html`로 돌려줘 `/privacy`, `/terms`, 소셜 로그인 콜백(`/?code=...`)을 새로고침해도 열립니다.
- 소셜 로그인 Redirect URI는 `https://<Vercel 도메인>/`입니다(base가 `/`). Preview 배포 주소는 매번 바뀌므로 provider 콘솔에는
  운영 도메인만 등록하고, Preview에서는 로그인이 실패할 수 있습니다.
- 응답 헤더(`vercel.json`): `Content-Security-Policy: frame-ancestors 'none'`, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`,
  `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy`(카메라·마이크·위치 등 미사용 기능 차단),
  `/assets/*`는 해시 파일명이라 1년 `immutable` 캐시. HSTS는 Vercel이 기본으로 붙입니다.

### 개인정보 처리방침·이용약관

`/privacy`, `/terms`는 로그인 없이 볼 수 있는 페이지입니다(`src/components/LegalPage.tsx`, 문구는 `src/i18n/dictionaries.ts`의
`legal.*`). 연락 이메일 `PRIVACY_CONTACT_EMAIL`과 시행일 `LEGAL_EFFECTIVE_DATE`는 `src/legal/legal.ts`의 상수이며, 운영 전에 실제 값으로 바꿉니다.
앱 심사(App Store·Google Play)에는 `https://<운영 도메인>/privacy`를 개인정보 처리방침 URL로 제출합니다.
