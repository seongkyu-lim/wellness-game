# Wellness Game

Apple HealthKit의 실제 걸음 수, 운동, 수면 요약을 서버로 전송하고 XP·레벨·캐릭터 스탯으로 변환하는 iOS + Spring Boot MVP입니다.

웹은 서버에 쌓인 결과를 보여 주는 조회 전용 대시보드입니다([`web/`](web/), [배포 페이지](https://seongkyu-lim.github.io/wellness-game/)). HealthKit은 서버나 브라우저에서 직접 읽을 수 없으므로 iOS 앱이 사용자의 명시적 권한을 받은 뒤 필요한 요약 데이터만 서버에 전송합니다.

## 구현 범위

- SwiftUI iOS 앱
  - 선택형 Apple·Google·Kakao·Naver 로그인과 로그아웃
  - 로그인 없이 유지되는 기기별 게스트 계정
  - HealthKit 사용 가능 여부 확인 및 읽기 권한 요청
  - 오늘 걸음 수 합계 조회
  - 오늘 `HKWorkout` 조회 및 운동 유형 매핑
  - 지난 밤 수면 구간 병합, 수면 시간 및 MVP 수면 점수 계산
  - Mock 데이터 fallback
  - async/await 기반 서버 동기화
  - 오늘 활동, 획득 XP, 레벨, 다음 레벨 XP, 스탯 표시
- Spring Boot API
  - `POST /api/health-activities/sync`
  - MySQL 활동 로그와 캐릭터 저장
  - 걸음·운동·수면 XP 계산
  - 여러 단계 레벨업 및 잔여 XP 이월
  - 활동별 스탯 증가
  - `external_key` unique constraint 기반 중복 XP 방지
- 테스트
  - XP 계산
  - 걸음 XP 상한
  - 수면 점수와 보너스/감소
  - 여러 단계 레벨업
  - 중복 활동 방지
  - API 요청/응답

## 프로젝트 구조

```text
.
├── backend/                     Spring Boot + MySQL API
├── ios/
│   ├── WellnessGame.xcodeproj   Xcode 프로젝트
│   └── WellnessGame/            SwiftUI·HealthKit 소스
├── web/                         React 조회 전용 대시보드 (GitHub Pages 배포)
├── docs/
│   ├── DESIGN.md                디자인 가이드 (토큰·컴포넌트·새싹이·모션)
│   └── design/                  디자인 시안 원본
└── docker-compose.yml           MySQL + API
```

웹과 iOS의 화면은 [디자인 가이드](docs/DESIGN.md)의 토큰과 규칙을 함께 따릅니다.

## 백엔드 실행

요구 사항:

- Java 17 이상
- MySQL 8.4 LTS 또는 Docker

> Gradle은 프로젝트에 포함된 Wrapper(`./gradlew`)를 사용하므로 별도 설치가 필요 없습니다.

가장 간단한 실행 방법:

```bash
docker compose up --build
```

- API: `http://localhost:8080`
- MySQL host port: `13306`
- DB/user/password: `wellness_game` / `wellness` / `wellness`

로컬 Gradle 실행:

```bash
docker compose up -d mysql
cd backend
DATABASE_URL='jdbc:mysql://localhost:13306/wellness_game?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
DATABASE_USERNAME=wellness \
DATABASE_PASSWORD=wellness \
./gradlew bootRun
```

테스트:

```bash
cd backend
./gradlew test
```

테스트는 MySQL 호환 모드의 인메모리 H2를 사용합니다.

### 소셜 로그인 서버 환경변수

iOS 앱은 각 SDK에서 받은 토큰을 아래 엔드포인트로 보내 서버 토큰(`{userId, displayName, accessToken, tokenType, expiresIn}`)으로 교환합니다. 값이 비어 있는 공급자의 엔드포인트는 `503`을 반환하고, 어떤 설정이 빠졌는지는 서버 로그에만 남습니다.

| 환경변수 | 쓰는 엔드포인트 | 값 |
|---|---|---|
| `OAUTH_GOOGLE_IOS_CLIENT_ID` | `POST /api/auth/google/native` | iOS OAuth 클라이언트 ID(ID 토큰의 `aud`) |
| `OAUTH_GOOGLE_CLIENT_ID`, `OAUTH_GOOGLE_CLIENT_SECRET` | `POST /api/auth/google`(웹), google/native 허용 `aud` | 웹 OAuth 클라이언트 |
| `OAUTH_KAKAO_NATIVE_APP_ID` | `POST /api/auth/kakao/native` | Kakao Developers의 **숫자 앱 ID**(`access_token_info`의 `app_id`) |
| `OAUTH_KAKAO_CLIENT_ID`, `OAUTH_KAKAO_CLIENT_SECRET` | `POST /api/auth/kakao`(웹) | REST API 키와 client secret |
| `OAUTH_NAVER_CLIENT_ID`, `OAUTH_NAVER_CLIENT_SECRET` | `POST /api/auth/naver`(웹), `POST /api/auth/naver/native` | 네이버 애플리케이션의 클라이언트 아이디·시크릿(iOS 앱과 같은 애플리케이션) |
| `OAUTH_APPLE_BUNDLE_ID` | `POST /api/auth/apple/native` | 앱 Bundle Identifier(identity token의 `aud`) |

> **카카오 키 주의:** iOS Build Settings의 `KAKAO_NATIVE_APP_KEY`(네이티브 앱 **키**, 문자열)와 서버의 `OAUTH_KAKAO_NATIVE_APP_ID`(앱 **ID**, 숫자)는 서로 다른 값입니다. 앱 ID는 Kakao Developers → 내 애플리케이션 → 앱 설정 → 요약 정보의 "앱 ID"입니다. 서버는 iOS가 보낸 액세스 토큰의 `app_id`가 이 값과 같은지 확인해 다른 앱에서 발급된 토큰을 거부합니다.

- 네이버 네이티브 교환은 `{"accessToken", "refreshToken"}`을 받습니다. 네이버에는 토큰의 발급 앱을 확인하는 API가 없어서, 서버가 `refreshToken`을 자기 client ID·secret으로 갱신해 이 앱에 발급된 토큰인지 확인하고, 새로 받은 access token으로만 프로필을 조회합니다.
- Apple 교환은 `{"identityToken", "fullName"(선택)}`을 받고 Apple JWKS로 서명·`iss`·`aud`·`exp`를 검증합니다. `fullName`은 100자를 넘으면 잘라서 씁니다.
- 로그인 방식별로 별도 계정입니다(`kakao:`, `naver:`, `google:`, `apple:`, `password:` 접두사). 같은 사람이 여러 방식으로 가입해도 계정을 합치지 않습니다.

### 레이트 리밋·로그인 잠금·JWT 설정 (#48, #55)

| 설정 키 (환경변수) | 기본값 | 설명 |
|---|---|---|
| `app.rate-limit.sync-per-minute` (`APP_RATE_LIMIT_SYNC_PER_MINUTE`) | `30` | `POST /api/health-activities/sync`, 인증 주체(userId)별 분당 한도 |
| `app.rate-limit.auth-per-minute` (`APP_RATE_LIMIT_AUTH_PER_MINUTE`) | `30` | `/api/auth/**` 전체(소셜 로그인·`/api/auth/{provider}` 포함), 클라이언트 IP별 분당 한도 |
| `app.rate-limit.login-per-minute` (`APP_RATE_LIMIT_LOGIN_PER_MINUTE`) | `10` | `/api/auth/login`·`/api/auth/signup`, IP별 분당 한도(위 한도와 함께 적용) |
| `app.rate-limit.trust-forwarded-header` (`APP_RATE_LIMIT_TRUST_FORWARDED_HEADER`) | `false` | `true`면 `X-Forwarded-For`의 **마지막 줄의 마지막 값**을 클라이언트 IP로 사용. 신뢰하는 리버스 프록시 한 단 뒤에서만 켤 것 |
| `app.rate-limit.max-keys` (`APP_RATE_LIMIT_MAX_KEYS`) | `100000` | 레이트 리밋 카운터·로그인 실패 기록이 각각 메모리에 두는 최대 키 수. 넘으면 만료 키를 먼저 지우고, 그래도 가득 차면 가장 오래된 항목을 내보냄 |
| `app.auth.max-failed-logins` (`APP_AUTH_MAX_FAILED_LOGINS`) | `5` | 같은 (아이디, IP) 연속 로그인 실패 허용 횟수 |
| `app.auth.max-failed-logins-per-username` (`APP_AUTH_MAX_FAILED_LOGINS_PER_USERNAME`) | `50` | IP와 무관한 아이디 전체 실패 허용 횟수(분산 공격 방어) |
| `app.auth.lockout-minutes` (`APP_AUTH_LOCKOUT_MINUTES`) | `15` | 잠금 시간이자 실패 기록 유지 시간(마지막 실패 기준, 분) |
| `auth.jwt.issuer` (`AUTH_JWT_ISSUER`) | `wellness-game` | 발급 토큰 `iss`, 검증 시 일치 필요 |
| `auth.jwt.audience` (`AUTH_JWT_AUDIENCE`) | `wellness-game-api` | 발급 토큰 `aud`, 검증 시 포함 필요 |
| `auth.jwt.legacy-cutoff` (`AUTH_JWT_LEGACY_CUTOFF`) | `2026-10-06T00:00:00Z` | `iss`·`aud`가 둘 다 없는 이전 형식 토큰은 `iat`이 이 시각 **이전**일 때만 허용. 비우면 이전 형식 토큰을 받지 않음 |

- 레이트 리밋 한도를 `0` 이하로 두면 해당 제한이 꺼지고, 잠금 한도를 `0` 이하로 두면 그 단계의 잠금이 꺼집니다.
- 한도를 넘으면 `429 Too Many Requests`, `Retry-After`(초) 헤더, 기존 오류 body(`timestamp`·`status`·`error`·`message`)로 응답합니다. CORS 응답에도 `Retry-After`를 노출합니다.
- 1분 **고정 윈도우**라서 경계 직전·직후에 몰리면 순간적으로 한도의 최대 2배까지 통과할 수 있습니다.
- 클라이언트 IP는 IPv4는 주소 그대로, IPv6는 **/64 접두사** 단위로 셉니다(주소를 바꿔 가며 한도를 피하는 것 방지). `X-Forwarded-For` 값이 64자를 넘거나 IP 리터럴이 아니면 연결 주소로 셉니다.
- 로그인 잠금은 (아이디, IP) 단위라서 다른 사람이 자기 IP에서 틀려도 피해자의 다른 IP 로그인은 막히지 않습니다. 대신 여러 IP로 나눠 시도하면 아이디 전체 한도(50회)에 걸려 그 아이디가 15분 잠깁니다. 시도는 비밀번호 비교 **전에** 먼저 세므로 동시 요청이 몰려도 한도보다 많이 비교하지 않습니다. 성공하면 그 (아이디, IP)의 실패 횟수를 지우고, 아이디 전체 기록에서는 이번 시도만 되돌립니다.
- 잠긴 동안에는 올바른 비밀번호도 기존 실패와 같은 `400 "아이디 또는 비밀번호가 올바르지 않습니다."`로 거부합니다. 없는 아이디도 같은 횟수 뒤 같은 방식으로 반응하고 bcrypt 비교를 한 번 거쳐 응답 시간으로 아이디 존재가 드러나지 않습니다. 가입의 "이미 사용 중인 아이디" 응답은 클라이언트 UX를 위해 유지하되 IP 레이트 리밋으로 열거 속도를 제한합니다.
- `AUTH_JWT_SECRET`은 32바이트 이상이어야 하고, 서로 다른 문자가 8종류 미만인 단순한 값(같은 문자 반복 등)이면 기동에 실패합니다. `openssl rand -base64 48` 같은 무작위 값을 쓰세요.
- **이전 형식 토큰 제거 계획:** #55 이전 서버가 발급한 토큰에는 `iss`·`aud`가 없습니다. 이 변경을 `legacy-cutoff`(기본 `2026-10-06T00:00:00Z`) 전에 배포하면 그 전에 발급된 토큰은 TTL(`AUTH_JWT_TTL`, 기본 30일)이 끝날 때까지 계속 쓰이고, cutoff 이후 시각이 찍힌 이전 형식 토큰은 위조로 보고 거부합니다. 배포가 cutoff보다 늦어지면 실제 배포 시각으로 `AUTH_JWT_LEGACY_CUTOFF`를 맞추세요. 마지막 이전 토큰은 cutoff + TTL(기본 2026-11-05) 이후 자연 소멸하므로, 그 뒤 릴리스에서 이 설정과 이전 형식 처리 코드를 제거합니다. `iss`나 `aud` 중 하나만 있는 토큰은 항상 거부합니다.

> **단일 인스턴스 한계:** 레이트 리밋 카운터와 로그인 실패·잠금 상태는 서버 메모리에만 있습니다. 재시작하면 초기화되고, 인스턴스를 여러 대 띄우면 인스턴스마다 따로 세므로 실제 한도가 대수만큼 늘어납니다. 수평 확장 시에는 Redis 등 공용 저장소 기반으로 바꿔야 합니다. 만료된 키는 주기적으로 정리하고 키 수 상한(`max-keys`)으로 메모리를 제한합니다.

#### 배포 체크리스트

- [ ] **리버스 프록시·로드 밸런서 뒤**에 배포한다면 `APP_RATE_LIMIT_TRUST_FORWARDED_HEADER=true`로 설정합니다. 끄면 서버가 보는 연결 주소가 모두 프록시 IP라서 **모든 사용자가 IP 하나를 공유**해 인증 한도(분당 30·10회)와 (아이디, IP) 잠금이 사실상 전체 사용자에게 함께 걸립니다.
- [ ] 켜기 전에 프록시가 **항상** `X-Forwarded-For`에 클라이언트 주소를 덧붙이는지(클라이언트가 보낸 값을 그대로 넘기기만 하지 않는지) 확인합니다. 서버는 마지막 줄의 마지막 값만 믿으므로 프록시가 덧붙이지 않으면 클라이언트가 IP를 위조할 수 있습니다. 프록시가 여러 단(CDN + LB 등)이면 마지막 값이 앞단 프록시 주소가 되므로 프록시 구성을 바꾸거나 이 구현을 확장해야 합니다.
- [ ] 프록시를 거치지 않고 직접 노출한다면 기본값(`false`)을 유지합니다. 켜면 누구나 헤더로 IP를 바꿔 한도를 피할 수 있습니다.
- [ ] 모바일 통신사 CGNAT·회사·학교 NAT에서는 많은 사용자가 같은 공인 IP를 쓸 수 있습니다. 같은 IP에서 정상 로그인이 몰려 `429`가 보이면 `APP_RATE_LIMIT_AUTH_PER_MINUTE`·`APP_RATE_LIMIT_LOGIN_PER_MINUTE`를 올립니다.
- [ ] 인스턴스를 2대 이상 띄우면 한도가 대수만큼 느슨해지므로 Redis 기반 구현을 먼저 검토합니다.
- [ ] `AUTH_JWT_LEGACY_CUTOFF`가 실제 배포 시각 이후인지 확인합니다(그래야 기존 사용자가 로그아웃되지 않습니다).

## API

### `POST /api/health-activities/sync`

요청 예시:

```json
{
  "userId": "test-user",
  "date": "2026-06-17",
  "activities": [
    {
      "type": "STEPS",
      "steps": 8500
    },
    {
      "type": "WORKOUT",
      "workoutType": "SWIMMING",
      "durationMinutes": 45,
      "calories": 420,
      "distanceMeters": 1200,
      "startedAt": "2026-06-17T07:00:00Z",
      "endedAt": "2026-06-17T07:45:00Z"
    },
    {
      "type": "SLEEP",
      "sleepMinutes": 450,
      "sleepScore": 90,
      "startedAt": "2026-06-16T22:30:00Z",
      "endedAt": "2026-06-17T06:00:00Z"
    }
  ]
}
```

응답에는 `gainedXp`, `levelUp`, 현재 캐릭터 상태와 활동별 XP 결과가 포함됩니다. 동일한 활동을 다시 보내면 `duplicate: true`, `gainedXp: 0`으로 반환합니다.

중복 키:

- `STEPS`: `userId + date + STEPS`
- `WORKOUT`: `userId + startedAt + endedAt + workoutType`
- `SLEEP`: `userId + startedAt + endedAt + SLEEP`

## XP 규칙

- 걸음: `steps / 200`, 최대 80 XP
- 운동: `durationMinutes × 2 + calories × 0.1 + distanceMeters × 0.01`
  - 수영 +20, 달리기 +10, 걷기 +5
- 수면:
  - 기본 XP는 수면 점수
  - 7~9시간이면 +30
  - 5시간 미만이면 최종 XP 50% 감소
- 다음 레벨 요구 XP: `현재 level × 100`
- 요구 XP를 넘으면 남은 XP를 이월하며 여러 레벨 상승을 처리합니다.

수면 점수:

- 7~9시간: 90
- 6~7시간: 70
- 5~6시간: 50
- 5시간 미만: 30

## iOS 앱 실행

요구 사항:

- macOS와 Xcode 16 이상
- iOS 17 이상 실제 기기 권장
- HealthKit을 사용할 수 있는 Apple Developer 서명 설정

1. Xcode에서 `ios/WellnessGame.xcodeproj`를 엽니다.
2. `WellnessGame` target의 Signing & Capabilities에서 Team과 고유 Bundle Identifier를 선택합니다.
3. `+ Capability`에서 **HealthKit**을 추가합니다.
4. `+ Capability`에서 **Sign in with Apple**을 추가합니다.
5. 백엔드를 실행합니다.
6. 실제 iPhone을 선택하고 앱을 실행합니다.
7. 로그인 여부와 관계없이 권한 요청 → 오늘 데이터 불러오기 → 서버 동기화를 누릅니다.

프로젝트에는 `WellnessGame.entitlements`가 포함되어 있지만, Apple Developer Team과 App ID에 HealthKit 및 Sign in with Apple capability가 활성화되어 있어야 실제 서명이 됩니다.

### 선택형 소셜 로그인과 게스트 이용

- Apple, Google, Kakao, Naver 로그인은 모두 선택 사항입니다. 로그인 화면을 건너뛰어도 HealthKit 조회, 서버 동기화, XP 획득 기능을 이용할 수 있습니다.
- 최초 실행 시 기기 안에 임의의 게스트 식별자를 만들고 `UserDefaults`에 유지합니다. 앱을 다시 실행해도 같은 게스트 캐릭터를 사용합니다.
- 로그인에 성공하면 공급자가 제공한 고유 사용자 식별자를 `apple:`, `google:`, `kakao:`, `naver:` 접두사와 함께 사용해 캐릭터를 분리합니다.
- 로그아웃하면 기존 게스트 식별자로 돌아가므로 게스트 진행 상황이 삭제되지 않습니다.
- Apple은 이름과 이메일을 최초 승인 시에만 제공할 수 있으므로 앱은 처음 받은 표시 이름을 기기에 저장합니다.
- Google 로그인은 GoogleSignIn iOS SDK 9.2 이상, Kakao 로그인은 Kakao iOS SDK 2.28 이상을 Swift Package Manager로 사용합니다.
- Naver 로그인은 네이버 아이디로 로그인 iOS SDK 5.1 이상을 Swift Package Manager로 사용합니다.
- 현재 MVP 서버는 앱이 보낸 사용자 식별자를 신뢰합니다. 운영 서비스에서는 각 공급자의 ID 토큰을 서버에서 검증하고 자체 세션을 발급해야 합니다.

### Google 로그인 설정

1. [Google Cloud Console](https://console.cloud.google.com/apis/credentials)에서 앱의 실제 Bundle Identifier와 일치하는 iOS OAuth 클라이언트를 만듭니다.
2. Xcode의 `WellnessGame` target → Build Settings → User-Defined에서 다음 값을 교체합니다.
   - `GOOGLE_CLIENT_ID`: iOS OAuth 클라이언트 ID
   - `GOOGLE_REVERSED_CLIENT_ID`: Google 설정에 표시되는 reversed client ID
3. 백엔드 인증을 추가할 때는 별도의 Web application client ID를 만들고 Google ID token의 서명, 발급자, audience를 서버에서 검증합니다.

공식 설정 문서: [Google Sign-In for iOS](https://developers.google.com/identity/sign-in/ios/start-integrating)

### Kakao 로그인 설정

1. [Kakao Developers](https://developers.kakao.com/)에서 앱을 만들고 카카오 로그인을 활성화합니다.
2. iOS 플랫폼에 앱의 실제 Bundle Identifier를 등록합니다.
3. Xcode의 `WellnessGame` target → Build Settings → User-Defined에서 `KAKAO_NATIVE_APP_KEY`를 네이티브 앱 키로 교체합니다.
4. 필요한 사용자 정보는 Kakao Developers의 동의항목에서 활성화합니다. 닉네임이나 이메일 동의가 없으면 앱은 공급자 기본 이름을 표시합니다.

`Info.plist`에는 카카오톡 실행 허용 스킴과 `kakao${NATIVE_APP_KEY}` 콜백 스킴이 포함되어 있습니다.

공식 설정 문서: [Kakao iOS SDK 시작하기](https://developers.kakao.com/docs/ko/ios/getting-started), [Kakao 로그인 iOS](https://developers.kakao.com/docs/ko/kakaologin/ios)

### Naver 로그인 설정

1. [네이버 개발자 센터](https://developers.naver.com/apps/)에서 애플리케이션을 등록하고 **네이버 로그인** API를 추가합니다.
2. iOS 환경에 앱의 실제 Bundle Identifier와 고유한 URL Scheme을 등록합니다.
3. Xcode의 `WellnessGame` target → Build Settings → User-Defined에서 다음 값을 교체합니다.
   - `NAVER_APP_NAME`: 네이버 로그인 화면에 표시할 서비스 이름
   - `NAVER_CLIENT_ID`: 발급받은 클라이언트 아이디
   - `NAVER_CLIENT_SECRET`: 발급받은 클라이언트 시크릿
   - `NAVER_URL_SCHEME`: 네이버 개발자 센터에 등록한 콜백 URL Scheme
4. 네이버 개발자 센터의 제공 정보에서 서비스에 필요한 프로필 항목을 선택합니다.

앱은 네이버 앱이 설치되어 있으면 네이버 앱을 우선 사용하고, 그렇지 않으면 인앱 브라우저로 로그인합니다. `Info.plist`에는 `naversearchapp`, `naversearchthirdlogin` 조회 스킴과 등록한 콜백 URL Scheme이 포함됩니다.

공식 설정 문서: [네이버 아이디로 로그인 iOS](https://developers.naver.com/docs/login/ios/ios.md)

### Info.plist 권한 문구

`ios/WellnessGame/Resources/Info.plist`에 다음 키가 포함되어 있습니다.

- `NSHealthShareUsageDescription`
- `NSHealthUpdateUsageDescription`

앱은 읽기 권한만 요청합니다. `NSHealthUpdateUsageDescription`은 HealthKit capability 검토와 향후 설정 호환성을 위해 앱이 데이터를 기록하지 않는다는 점을 명시합니다.

요청하는 읽기 타입:

- `stepCount`
- `workoutType`
- `activeEnergyBurned`
- `heartRate`
- `sleepAnalysis`

심박수는 MVP 권한 범위에는 포함되지만 서버 전송이나 XP 계산에는 사용하지 않습니다.

### 실제 기기 서버 주소

기본 API 주소는 `http://127.0.0.1:8080`입니다. 이는 Simulator에서 Mac의 로컬 서버에 접속할 때 사용할 수 있습니다.

실제 iPhone에서는 `ios/WellnessGame/Networking/NetworkClient.swift`의 `baseURL`을 Mac 또는 배포 서버의 접근 가능한 HTTPS 주소로 변경해야 합니다. 로컬 네트워크를 사용할 경우 iPhone과 Mac을 같은 네트워크에 연결하고 Mac의 LAN IP를 사용하세요.

운영 배포에서는 HTTPS만 사용해야 합니다.

## HealthKit 제한과 개인정보

- HealthKit 데이터는 민감정보입니다. 이 MVP는 XP 계산에 필요한 요약 데이터만 서버에 보냅니다.
- 원본 `HKObject`나 심박수 샘플 전체를 서버에 저장하지 않습니다.
- 사용자가 권한을 거부해도 앱은 종료되지 않고 오류 메시지를 표시합니다.
- HealthKit은 실제 기기에서 테스트해야 합니다. Simulator의 HealthKit 데이터는 없거나 제한적일 수 있습니다.
- 이 앱은 진단, 치료, 의학적 판단을 제공하지 않습니다.
- App Store 제출 전 개인정보 처리방침, 데이터 보관/삭제 정책, HealthKit 사용 목적을 정확히 작성해야 합니다.
- HealthKit 데이터를 광고, 마케팅 또는 데이터 판매에 사용해서는 안 됩니다.

## Mock 데이터

화면의 `Mock 데이터 사용`을 켜면 HealthKit 대신 테스트용 걸음·수영·수면 데이터가 로드됩니다. 실제 HealthKit 연동이 기본이며 Mock은 개발 fallback입니다.

## MVP 이후 TODO

- Apple·Google·Kakao·Naver 토큰 서버 검증과 자체 세션 발급
- 게스트 진행 상황을 소셜 계정으로 이전하는 계정 연결 기능
- 운영 DB migration 도구(Flyway/Liquibase)
- 서버 HTTPS 배포 및 iOS 환경별 API 설정
- 백그라운드 동기화와 실패 요청 재시도 저장소
- HealthKit anchored query 기반 증분 동기화
- 개인정보 삭제 API와 보관 기간 정책
- iOS unit/UI test target 및 실제 기기 통합 테스트
