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

## 빌드

```bash
npm run build   # 타입 체크 + dist/ 생성
```
