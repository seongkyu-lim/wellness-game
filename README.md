# wellness-game



너는 iOS 앱 + 백엔드 개발을 함께 진행하는 시니어 풀스택 개발 에이전트다.

목표:
Apple 건강/피트니스 데이터를 활용한 “생활형 RPG” 앱의 실제 동작 가능한 MVP를 만든다.
웹 프로토타입은 만들지 않는다.
바로 iOS 앱에서 HealthKit 데이터를 읽고, 서버 API로 전송한 뒤, 서버에서 XP와 레벨업을 계산하는 구조로 구현한다.

핵심 컨셉:
- 사용자의 실제 Apple 건강/피트니스 데이터를 RPG 경험치로 변환한다.
- 운동 완료, 수면 완료, 걸음 수 데이터를 기반으로 XP를 계산한다.
- XP가 누적되면 캐릭터가 레벨업한다.
- 첫 MVP는 복잡한 게임이 아니라 “건강 데이터 → 점수 계산 → 캐릭터 성장” 루프를 완성하는 것이 목표다.

중요 전제:
- 웹 브라우저에서는 HealthKit에 직접 접근할 수 없다.
- 따라서 iOS 앱에서 HealthKit 데이터를 읽어야 한다.
- HealthKit 데이터는 사용자의 명시적 권한을 받아야 한다.
- 서버는 Apple HealthKit에 직접 접근할 수 없다.
- iOS 앱이 HealthKit 데이터를 읽고 서버로 전송한다.
- HealthKit 실제 연동을 구현한다.
- Mock 데이터는 테스트용 fallback으로만 둔다.

권장 기술 스택:
1. iOS 앱
   - 우선 Swift + SwiftUI로 구현한다.
   - HealthKit 연동은 Apple HealthKit framework를 사용한다.
   - iOS 앱에서 HealthKit 권한 요청, 데이터 조회, 서버 전송까지 구현한다.

2. Backend
   - Spring Boot + Java 또는 Kotlin으로 구현한다.
   - 기존 백엔드 프로젝트가 있으면 현재 구조를 분석하고 거기에 맞춘다.
   - 없으면 Spring Boot 기준으로 신규 구성한다.
   - DB는 PostgreSQL 또는 MySQL 중 현재 프로젝트에 맞춰 선택한다.
   - 우선 인증은 단순화한다. userId는 임시 UUID 또는 고정 테스트 유저를 사용한다.

개발 단계:

STEP 1. 프로젝트 구조 분석
- 현재 프로젝트 구조를 먼저 확인한다.
- iOS 앱 프로젝트가 있는지 확인한다.
- 백엔드 프로젝트가 있는지 확인한다.
- 없다면 각각 최소 MVP 프로젝트 구조를 제안하고 생성한다.
- 어떤 파일을 생성/수정할지 먼저 짧게 설명한다.

STEP 2. iOS HealthKit 권한 요청 구현
- HealthKit 사용 가능 여부를 체크한다.
- 다음 데이터 읽기 권한을 요청한다.
  - stepCount
  - workouts
  - activeEnergyBurned
  - heartRate
  - sleepAnalysis
- Info.plist에 필요한 HealthKit permission description을 추가한다.
- HealthKit capability 설정이 필요하다는 안내를 코드 주석 또는 README에 남긴다.

구현해야 할 iOS 파일 예시:
- HealthKitManager.swift
- HealthDataProvider.swift
- AppleHealthKitProvider.swift
- MockHealthDataProvider.swift
- HealthActivityDTO.swift

STEP 3. HealthKit 데이터 조회 구현
오늘 기준으로 다음 데이터를 읽는다.

1. 걸음 수
- 오늘 00:00부터 현재까지 stepCount 합계 조회

2. 운동 기록
- 오늘의 HKWorkout 조회
- workoutActivityType
- startDate
- endDate
- duration
- totalEnergyBurned
- totalDistance
- 가능하면 swimming/running/walking 등 activity type 매핑

3. 수면 데이터
- 지난 밤 기준 sleepAnalysis 조회
- asleep 상태의 총 시간을 계산
- 수면 점수는 Apple이 직접 제공하지 않을 수 있으므로 MVP에서는 자체 계산한다.
- 수면 시간 기반으로 sleepScore를 계산한다.
  - 7~9시간: 90
  - 6~7시간: 70
  - 5~6시간: 50
  - 5시간 미만: 30

STEP 4. iOS 내부 Activity DTO 변환
HealthKit 원본 객체를 서버 전송용 DTO로 변환한다.

Activity 타입:
- STEPS
- WORKOUT
- SLEEP

DTO 예시:
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
      "startedAt": "...",
      "endedAt": "..."
    },
    {
      "type": "SLEEP",
      "sleepMinutes": 450,
      "sleepScore": 86,
      "startedAt": "...",
      "endedAt": "..."
    }
  ]
}

STEP 5. iOS 서버 전송 구현
- URLSession 또는 async/await 기반 NetworkClient를 만든다.
- POST /api/health-activities/sync 로 오늘 활동 데이터를 전송한다.
- 서버 응답으로 XP 계산 결과와 캐릭터 상태를 받는다.
- 네트워크 실패 시 에러 메시지를 표시한다.
- 나중에 재시도 가능하도록 구조를 분리한다.

iOS 화면 MVP:
- SwiftUI로 간단한 화면을 만든다.
- 버튼:
  - HealthKit 권한 요청
  - 오늘 데이터 불러오기
  - 서버에 동기화
- 표시:
  - 걸음 수
  - 운동 목록
  - 수면 시간
  - 오늘 획득 XP
  - 현재 레벨
  - 현재 XP / 다음 레벨 필요 XP
  - 레벨업 여부

STEP 6. 백엔드 API 구현
Spring Boot 기준으로 다음 API를 구현한다.

1. Health activity sync API
POST /api/health-activities/sync

Request:
{
  "userId": "test-user",
  "date": "2026-06-17",
  "activities": [...]
}

Response:
{
  "userId": "test-user",
  "date": "2026-06-17",
  "gainedXp": 280,
  "levelUp": true,
  "character": {
    "level": 3,
    "currentXp": 40,
    "totalXp": 340,
    "nextLevelXp": 300,
    "stats": {
      "str": 3,
      "vit": 5,
      "int": 1,
      "discipline": 4,
      "recovery": 2
    }
  },
  "activityResults": [
    {
      "type": "WORKOUT",
      "source": "SWIMMING",
      "gainedXp": 152,
      "message": "수영 완료 +152 XP"
    },
    {
      "type": "SLEEP",
      "gainedXp": 86,
      "message": "수면 회복 보너스 +86 XP"
    },
    {
      "type": "STEPS",
      "gainedXp": 42,
      "message": "걸음 수 보상 +42 XP"
    }
  ]
}

STEP 7. 백엔드 도메인 모델 설계
최소 도메인:
- UserCharacter
- CharacterStats
- HealthActivity
- ActivityXpResult

DB 저장:
- character
  - id
  - user_id
  - level
  - current_xp
  - total_xp
  - str
  - vit
  - int_stat
  - discipline
  - recovery
  - created_at
  - updated_at

- health_activity_log
  - id
  - user_id
  - activity_date
  - type
  - source_type
  - duration_minutes
  - calories
  - distance_meters
  - steps
  - sleep_minutes
  - sleep_score
  - gained_xp
  - external_key
  - started_at
  - ended_at
  - created_at

중복 방지:
- 같은 운동/수면 데이터가 여러 번 전송될 수 있으므로 중복 적립을 방지한다.
- external_key를 만든다.
- 예:
  - WORKOUT: userId + startedAt + endedAt + workoutType
  - SLEEP: userId + startedAt + endedAt + "SLEEP"
  - STEPS: userId + date + "STEPS"
- DB에 unique constraint를 둔다.
- 이미 처리된 activity는 XP를 다시 지급하지 않는다.

STEP 8. XP 계산 로직 구현
서버에서 계산한다.
iOS에서는 원본 데이터를 보내고, 게임 로직은 서버가 책임진다.

점수 계산 규칙:
1. Steps XP
- steps / 200
- 최대 80 XP 제한

2. Workout XP
- durationMinutes * 2
- calories * 0.1
- distanceMeters * 0.01
- swimming bonus +20
- running bonus +10
- walking bonus +5

3. Sleep XP
- sleepScore 그대로 기본 XP
- 7~9시간이면 +30 recovery bonus
- 5시간 미만이면 XP 50% 감소

4. Level system
- nextLevelXp = level * 100
- XP가 nextLevelXp 이상이면 레벨업
- 여러 레벨업도 처리 가능해야 한다.
- 남은 XP는 이월한다.

5. Stats 증가
- WORKOUT:
  - str +1
  - vit +1
- SWIMMING:
  - vit +2
- STEPS:
  - discipline +1
- SLEEP:
  - recovery +1
  - 7시간 이상이면 vit +1

STEP 9. 테스트 작성
가능하면 다음 테스트를 작성한다.
- XP 계산 테스트
- 레벨업 테스트
- 중복 activity 처리 테스트
- 수면 점수 계산 테스트
- steps 최대 XP 제한 테스트

STEP 10. README 작성
README에 다음 내용을 정리한다.
- 실행 방법
- iOS HealthKit capability 설정 방법
- Info.plist 권한 문구
- 서버 실행 방법
- API endpoint
- HealthKit 데이터 접근 제한 사항
- 실제 기기에서 테스트해야 하는 이유
- Simulator에서는 HealthKit 데이터가 제한적일 수 있다는 점
- App Store 심사 시 개인정보 처리방침과 HealthKit 사용 목적 설명이 필요하다는 점

구현 시 주의사항:
- HealthKit 데이터는 민감정보이므로 최소 데이터만 요청한다.
- 사용자가 권한을 거부해도 앱이 죽지 않게 처리한다.
- iOS 앱에는 의료 조언처럼 보이는 문구를 넣지 않는다.
- “진단”, “치료”, “의학적 판단” 표현은 피한다.
- 데이터는 XP 계산과 캐릭터 성장에만 사용한다는 구조로 만든다.
- 광고/마케팅 목적 사용은 고려하지 않는다.
- 서버에는 가능한 한 필요한 요약 데이터만 저장한다.
- 원본 HealthKit 객체 전체를 저장하지 않는다.

최종 완료 기준:
- iOS 앱에서 HealthKit 권한을 요청할 수 있다.
- iOS 앱에서 오늘 걸음 수를 조회할 수 있다.
- iOS 앱에서 오늘 운동 기록을 조회할 수 있다.
- iOS 앱에서 지난 밤 수면 데이터를 조회할 수 있다.
- iOS 앱에서 조회한 데이터를 서버 DTO로 변환할 수 있다.
- iOS 앱에서 서버 API로 데이터를 전송할 수 있다.
- 백엔드에서 활동 데이터를 저장할 수 있다.
- 백엔드에서 XP를 계산할 수 있다.
- 백엔드에서 레벨업을 처리할 수 있다.
- 백엔드에서 중복 XP 적립을 방지할 수 있다.
- iOS 앱에서 서버 응답을 받아 캐릭터 성장 결과를 표시할 수 있다.
- README에 실행 방법과 HealthKit 설정 방법이 정리되어 있다.

진행 방식:
1. 먼저 현재 프로젝트 구조를 분석한다.
2. iOS 앱과 백엔드 중 어떤 프로젝트가 있는지 확인한다.
3. 없다면 SwiftUI iOS 앱과 Spring Boot 백엔드 MVP 구조를 생성한다.
4. 단계별로 구현한다.
5. 각 단계가 끝날 때마다 무엇을 구현했는지 요약한다.
6. 마지막에 실행 방법, 수정 파일 목록, 남은 TODO를 알려준다.


