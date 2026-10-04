# 게임 규칙 SPEC v1

Apple 건강·피트니스 데이터를 동기부여 중심의 단순·투명한 규칙으로 게임화한다.
XP·스탯·레벨 공식과 오늘의 목표(goals) 응답을 정의한다.

본 문서는 이슈 #21의 확정 규칙이며, 백엔드 구현(`ActivityXpCalculator`,
`HealthActivitySyncService`, `UserCharacter`)과 테스트의 기준값 출처다.

---

## 1. 레벨 곡선

```
nextLevelXp(level) = 100 + (level - 1) * 50
```

| 레벨 | 다음 레벨까지 XP |
|------|-----------------|
| 1    | 100 |
| 2    | 150 |
| 3    | 200 |
| 4    | 250 |
| n    | 100 + (n-1)*50 |

- XP가 임계치를 넘으면 잔여 XP를 이월하며 여러 단계 동시 레벨업 가능.

---

## 2. XP 공식

### STEPS (걸음)

```
xp = floor(steps / 1000) * 5
if steps >= 8000: xp += 30      # 목표 달성 보너스
xp = min(xp, 100)               # 상한
```

- 스탯: `DISCIPLINE +1`, 목표(8,000보) 달성 시 `DISCIPLINE +1` 추가.
- 재동기화: 걸음 로그는 하루(사용자·날짜)에 1개이며, 같은 날 더 큰 걸음 수가 오면 갱신한다.
  - 새 걸음 수로 다시 계산한 XP에서 이미 지급한 XP를 뺀 차액만 지급한다(지급한 XP는 회수하지 않음).
  - `DISCIPLINE +1`(기본)은 그날 첫 로그 생성 시 1회, 목표 보너스 `DISCIPLINE +1`은 목표 미달→달성으로 바뀔 때 1회.
  - 걸음 수가 같거나 작으면 변화 없음(중복 응답). 늘었지만 차액이 0이면 갱신하고 0 XP로 응답한다.
  - 한 요청에 STEPS가 여러 개면 가장 큰 값 하나만 반영한다.

### WORKOUT (운동)

```
xp = durationMinutes * 3 + floor(calories / 10) + 종목보너스
xp = clamp(xp, 0, 200)          # 상한 200
```

종목보너스:

| 종목 | 보너스 | 분류 |
|------|--------|------|
| SWIMMING | 20 | 유산소 |
| RUNNING  | 15 | 유산소 |
| CYCLING  | 15 | 유산소 |
| WALKING  | 5  | 유산소 |
| STRENGTH_TRAINING | 15 | 근력 |
| OTHER    | 0  | 기타 |

- 스탯 분기:
  - 근력(STRENGTH_TRAINING): `STR +2`, `VIT +1`
  - 유산소(SWIMMING/RUNNING/CYCLING/WALKING): `VIT +2`, `STR +1`
  - 기타(OTHER): `STR +1`, `VIT +1`
- 거리(distanceMeters)는 XP 계산에서 제외(v1). 로그에는 계속 저장.

### SLEEP (수면)

구간 정액 XP:

| 수면 시간(분) | XP | 비고 |
|--------------|----|------|
| 420 ~ 540 (7~9시간) | 60 | 권장 |
| 360 ~ 419 (6~7시간) | 40 | |
| 300 ~ 359 (5~6시간) | 25 | |
| 541 이상 (9시간 초과) | 40 | 과다 수면 |
| 300 미만 (5시간 미만) | 10 | |

```
if sleepScore >= 80: xp += 20   # 수면 품질 보너스
```

- 수면 점수(`sleepScore`): 요청에 값이 있으면 사용, 없으면 시간 기반 추정.
  - 420~540 → 90, 360 이상 → 70, 300 이상 → 50, 그 외 → 30
- 스탯: `RECOVERY +1`, 그리고 수면 품질을 출처로 `INT` 부여
  - 점수 80 이상 → `INT +2`, 미만 → `INT +1`

---

## 3. goals (오늘의 목표 달성 현황)

동기화 응답(`/api/health-activities/sync`)에 `goals` 배열을 추가한다.
현재값은 해당 사용자의 그날(요청 `date`) 저장된 활동 기록 누적값으로 계산한다.

| type | target | 단위 | current 산출 |
|------|--------|------|-------------|
| STEPS   | 8000 | steps   | 그날 STEPS 로그의 걸음 수(하루 1개, 최댓값) |
| WORKOUT | 30   | minutes | WORKOUT 활동 시간(분) 합 |
| SLEEP   | 420  | minutes | SLEEP 활동 수면 시간(분) 중 최댓값 |

응답 형태:

```json
"goals": [
  { "type": "STEPS",   "target": 8000, "current": 8500, "unit": "steps",   "achieved": true },
  { "type": "WORKOUT", "target": 30,   "current": 45,   "unit": "minutes", "achieved": true },
  { "type": "SLEEP",   "target": 420,  "current": 450,  "unit": "minutes", "achieved": true }
]
```

- `achieved = current >= target`.
- 세 목표는 활동 유무와 관계없이 항상 반환(활동 없으면 current = 0).

---

## 4. 검증 기준값 (테스트 앵커)

| 케이스 | 기대값 |
|--------|--------|
| STEPS 8,500 | 70 XP (8*5 + 30) |
| STEPS 50,000 | 100 XP (상한) |
| STEPS 2,000 | 10 XP |
| WORKOUT SWIMMING 45분·420kcal | 197 XP (135 + 42 + 20) |
| WORKOUT RUNNING 30분·0kcal | 105 XP (90 + 15) |
| SLEEP 450분·점수 90 | 80 XP (60 + 20) |
| SLEEP 240분·점수 30 | 10 XP (10) |
| addXp(650) | Lv.4, currentXp 200, nextLevelXp 250 |
