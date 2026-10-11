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
  - 401을 받으면 웹은 저장된 세션을 지우고 로그인 안내 화면으로 돌아갑니다.
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
`accessToken`(Bearer, `expiresIn` 초 유효) 반환 → localStorage(`wellness.session`)에 세션 저장.
토큰이 없는 예전 형식의 세션이나 만료된 세션은 로그아웃 상태로 처리됩니다.

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
API 클라이언트 헤더·401 처리, 세션 로드·만료(`lib/auth.ts`), 로그인 버튼 배치, CSP 생성을 검사합니다.
GitHub Pages 워크플로는 빌드 전에 `npm test`를 실행합니다.

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
  백엔드 배포 전 Pages 배포(저장소 변수 미설정)가 끊기지 않도록 빌드 실패 대신 경고로 둡니다.
- 소셜 로그인은 provider 페이지로의 최상위 이동이고 토큰 교환은 백엔드가 하므로 `connect-src`에 provider 도메인이 없습니다.
- 외부 리소스(스크립트·이미지·폰트 CDN 등)를 추가하면 `csp.ts`의 지시문도 함께 고치세요.
- **`frame-ancestors`(클릭재킹 방지)는 meta로 지정할 수 없습니다.** 웹을 직접 서빙하는 서버·CDN에서
  `Content-Security-Policy: frame-ancestors 'none'` 응답 헤더(구형 브라우저용 `X-Frame-Options: DENY` 병행)를 설정하세요.
  GitHub Pages는 응답 헤더를 바꿀 수 없으므로, 필요하면 Cloudflare 등 헤더를 설정할 수 있는 프록시·호스팅을 앞에 둡니다.

### 웹 토큰 저장 위험과 완화

서버 발급 Bearer 토큰을 `localStorage`(`wellness.session`)에 저장하므로, 페이지에서 임의 스크립트가 실행되면(XSS)
토큰이 탈취될 수 있습니다. 현재 완화책:

- CSP로 인라인·외부 스크립트 실행을 막고(`script-src 'self'`), 데이터 전송 대상을 API 오리진으로 제한합니다(`connect-src`).
- React 기본 이스케이프만 사용하고 `dangerouslySetInnerHTML`을 쓰지 않습니다. 서버 메시지도 텍스트로만 표시합니다.
- 웹은 조회 전용이라 토큰이 유출돼도 웹 화면으로 기록을 만들 수 없지만, 토큰 자체는 동기화 API도 호출할 수 있으므로
  **토큰 유효 기간을 짧게** 유지하세요(백엔드 `AUTH_JWT_TTL`, 기본 30일). 만료된 세션은 로드 시 지워지고, 401을 받으면 즉시 로그아웃합니다.
- 공용 PC에서는 사용 후 로그아웃하도록 안내합니다(로그아웃 시 저장된 세션 삭제).
- 장기적으로는 HttpOnly·Secure·SameSite 쿠키 기반 세션(같은 사이트 배포 + CSRF 대책)으로 옮기는 것이 가장 안전합니다.

## GitHub Pages 배포

`dev` 브랜치에 `web/` 변경이 push되면 `.github/workflows/web-pages.yml`이 빌드해
`https://seongkyu-lim.github.io/wellness-game/`에 배포합니다. Actions 탭에서 수동 실행(`workflow_dispatch`)도 가능합니다.
`dev`에서 배포하므로 **Settings › Environments › github-pages › Deployment branches**에 `dev`가 허용돼 있어야 합니다.

- 하위 경로 배포라 빌드 시 `actions/configure-pages`가 알려주는 경로를 `--base`로 지정합니다. 소셜 로그인 Redirect URI도
  `https://seongkyu-lim.github.io/wellness-game/`로 자동 계산되므로 각 provider 콘솔에 이 주소를 등록합니다.
- 빌드 환경변수는 저장소 **Settings › Secrets and variables › Actions › Variables**에 설정합니다.
  (`VITE_API_BASE_URL`, `VITE_GOOGLE_CLIENT_ID`, `VITE_KAKAO_CLIENT_ID`, `VITE_NAVER_CLIENT_ID`)
  모두 브라우저에 노출되는 공개 값이므로 Secrets가 아닌 Variables를 사용합니다.
- 백엔드를 배포하기 전에는 API 주소가 없어 로그인 화면까지만 동작합니다. 백엔드 배포 후
  `VITE_API_BASE_URL`과 백엔드 `CORS_ALLOWED_ORIGINS`(`https://seongkyu-lim.github.io`)를 설정하세요.
