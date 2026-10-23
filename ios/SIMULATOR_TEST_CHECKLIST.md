# Xcode 시뮬레이터 수동 테스트 체크리스트

시뮬레이터에서는 HealthKit이 동작하지 않으므로(`isHealthDataAvailable() == false`),
건강 데이터 관련 기능은 **Mock 데이터 토글**(대시보드 하단 개발자 옵션)을 켜고 테스트한다.

- Mock 데이터 값: 걸음 8,500보 / 수영 45분 420kcal / 수면 8시간
- 백엔드가 필요한 항목은 먼저 서버를 띄운다:
  ```bash
  cd /Users/limseongkyu/Project/wellness-game
  docker-compose up -d   # MySQL(13306) + Spring Boot 백엔드(8080)
  ```
- 앱의 API 베이스 URL: `http://127.0.0.1:8080` (`Networking/NetworkClient.swift`)

---

## 1. 백엔드 없이 바로 테스트 가능 (UI/네비게이션)

- [ ] 앱 실행 시 메인 대시보드가 정상 렌더링된다 (`Views/ContentView.swift`)
- [ ] 헤더에 오늘 날짜가 한국어 요일 포함으로 표시된다
- [ ] 동기화 전에는 캐릭터 영역에 "새싹이가 기다리고 있어요" 플레이스홀더가 표시된다
  - XP 게이지·능력치 타일은 서버 동기화 성공 후에만 표시됨 (`syncResponse` 있을 때) → 2번 섹션에서 확인
- [ ] 헤더의 로그인 버튼 → 로그인 시트가 열리고 닫힌다 (`Views/LoginSheetView.swift`)
- [ ] 로그인 시트에서 **Google 버튼만 활성화**, Apple/Kakao/Naver는 비활성(회색) 상태다 (#36)
- [ ] Mock 데이터 토글 on/off 가 동작한다 (off 상태에선 시뮬레이터라 건강 데이터 없음)
- [ ] Pull-to-refresh 제스처가 동작한다 (백엔드 없으면 네트워크 에러 — 에러 처리 확인 겸용)

## 2. Mock 데이터 + 로컬 백엔드 필요 (동기화/게임 로직)

- [ ] Mock 토글 켜기 → 오늘의 활동에 걸음 8,500 / 수영 45분 / 수면 8시간 표시
- [ ] 앱 시작 시 자동 동기화가 실행된다 (`POST /api/health-activities/sync`)
- [ ] 동기화 성공 시 플레이스홀더가 사라지고 캐릭터 히어로 영역이 표시된다
  - [ ] 레벨/단계 배지, XP 게이지(현재XP / 다음레벨XP)
  - [ ] 능력치 타일 STR/VIT/DISC/REC
- [ ] 동기화 후 획득 내역 섹션에 활동별 XP 결과가 표시된다
- [ ] XP 게이지가 서버 응답값으로 갱신되고 XP 링 애니메이션(0.8초)이 재생된다
- [ ] XP 누적 시 레벨업 배지("레벨업! ✨")가 표시된다
- [ ] 같은 날 재동기화 시 중복 활동은 XP가 다시 지급되지 않는다 (중복 표시 확인)
- [ ] Pull-to-refresh 는 5분 간격 정책을 무시하고 강제 동기화한다
- [ ] 홈 화면으로 나갔다 5분 이내 복귀 → 동기화 안 함 / 5분 이후 복귀 → 동기화 실행 (`ViewModels/AutoSyncPolicy.swift`, 300초)
- [ ] Mock 토글을 껐다 켜면 즉시 재동기화된다
- [ ] 레벨 구간별 캐릭터 성장 단계 렌더링 (`Views/CharacterAvatarView.swift`)
  - [ ] Lv.1~2 씨앗 → Lv.3~5 새싹 → Lv.6~9 줄기 → Lv.10~14 어린나무 → Lv.15~19 나무 → Lv.20+ 개화

## 3. 추가 설정이 있어야 테스트 가능 (Google 로그인)

시뮬레이터에서 가능하지만 OAuth 설정(GIDClientID, URL Scheme)이 되어 있어야 한다.

- [ ] Google 버튼 → OAuth 플로우가 열린다
- [ ] 로그인 성공 시 시트에 프로바이더 · 사용자명이 표시된다
- [ ] userId 가 `guest:*` → `google:*` 로 바뀌고, 로그인 계정 기준 캐릭터로 동기화된다
- [ ] 로그아웃 버튼 → 게스트 모드로 복귀한다
- [ ] 앱 재시작 시 세션이 복구된다 (`SocialLoginService.restoreSessionIfNeeded()`)

## 4. 시뮬레이터에서 불가 — 실기기에서만

- [ ] HealthKit 권한 요청 다이얼로그 (걸음/운동/수면/심박수)
- [ ] 실제 걸음 수 수집 및 XP 반영
- [ ] 실제 운동 기록(운동 타입별) 수집
- [ ] 수면 분석 데이터 수집
- [ ] 심박수 (Apple Watch 등 센서 필요)

---

## 참고: 단위 테스트 (⌘U)

수동 테스트 전 회귀 확인용 — `WellnessGameTests/`
- `AutoSyncPolicyTests` — 5분 간격 정책 경계값
- `GrowthStageTests` — 레벨별 성장 단계 매핑
- `DisplayLogicTests`, `ThemeColorTests`
