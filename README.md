# Wellness Game

Apple HealthKit의 실제 걸음 수, 운동, 수면 요약을 서버로 전송하고 XP·레벨·캐릭터 스탯으로 변환하는 iOS + Spring Boot MVP입니다.

웹 프로토타입은 포함하지 않습니다. HealthKit은 서버나 브라우저에서 직접 읽을 수 없으므로 iOS 앱이 사용자의 명시적 권한을 받은 뒤 필요한 요약 데이터만 서버에 전송합니다.

## 구현 범위

- SwiftUI iOS 앱
  - HealthKit 사용 가능 여부 확인 및 읽기 권한 요청
  - 오늘 걸음 수 합계 조회
  - 오늘 `HKWorkout` 조회 및 운동 유형 매핑
  - 지난 밤 수면 구간 병합, 수면 시간 및 MVP 수면 점수 계산
  - Mock 데이터 fallback
  - async/await 기반 서버 동기화
  - 오늘 활동, 획득 XP, 레벨, 다음 레벨 XP, 스탯 표시
- Spring Boot API
  - `POST /api/health-activities/sync`
  - PostgreSQL 활동 로그와 캐릭터 저장
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
├── backend/                     Spring Boot + PostgreSQL API
├── ios/
│   ├── WellnessGame.xcodeproj   Xcode 프로젝트
│   └── WellnessGame/            SwiftUI·HealthKit 소스
└── docker-compose.yml           PostgreSQL + API
```

## 백엔드 실행

요구 사항:

- Java 17 이상
- Maven 3.6.3 이상
- PostgreSQL 17 또는 Docker

가장 간단한 실행 방법:

```bash
docker compose up --build
```

- API: `http://localhost:8080`
- PostgreSQL host port: `15432`
- DB/user/password: `wellness_game` / `wellness` / `wellness`

로컬 Maven 실행:

```bash
docker compose up -d postgres
cd backend
DATABASE_URL=jdbc:postgresql://localhost:15432/wellness_game \
DATABASE_USERNAME=wellness \
DATABASE_PASSWORD=wellness \
mvn spring-boot:run
```

테스트:

```bash
cd backend
mvn test
```

테스트는 PostgreSQL 호환 모드의 인메모리 H2를 사용합니다.

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
4. 백엔드를 실행합니다.
5. 실제 iPhone을 선택하고 앱을 실행합니다.
6. 앱에서 순서대로 권한 요청 → 오늘 데이터 불러오기 → 서버 동기화를 누릅니다.

프로젝트에는 `WellnessGame.entitlements`가 포함되어 있지만, Apple Developer Team과 App ID에 HealthKit capability가 활성화되어 있어야 실제 서명이 됩니다.

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

- 고정 `test-user` 대신 Sign in with Apple 기반 사용자 인증
- 운영 DB migration 도구(Flyway/Liquibase)
- 서버 HTTPS 배포 및 iOS 환경별 API 설정
- 백그라운드 동기화와 실패 요청 재시도 저장소
- HealthKit anchored query 기반 증분 동기화
- 개인정보 삭제 API와 보관 기간 정책
- iOS unit/UI test target 및 실제 기기 통합 테스트

