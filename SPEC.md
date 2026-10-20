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
  - `DISCIPLINE +1`(기본)은 그날 로그에 처음 XP가 지급될 때 1회(1,000보 미만으로 0 XP면 다음 지급 때로 미룸),
    목표 보너스 `DISCIPLINE +1`은 목표 미달→달성으로 바뀔 때 1회.
  - 요청 `date`가 서버 기준 오늘보다 뒤(내일)면 걸음 수는 건너뛴다(#107).
  - 걸음 수가 같거나 작으면 변화 없음(중복 응답). 늘었지만 차액이 0이면 갱신하고 0 XP로 응답한다.
  - 한 요청에 STEPS가 여러 개면 가장 큰 값 하나만 반영한다.

### WORKOUT (운동)

```
if 구간(endedAt - startedAt) < 5분 or durationMinutes < 5: xp = 0   # 최소 운동 시간(#107)
else: xp = durationMinutes * 3 + floor(calories / 10) + 종목보너스
xp = clamp(xp, 0, 200)          # 상한 200
```

- 5분 미만 운동은 기록(로그 저장, goals 반영, 재전송 시 중복)되지만 XP·스탯은 없다.
  응답은 `duplicate=false`, 0 XP, 메시지 `"<source> 기록됨 (5분 미만 운동은 XP·스탯이 없어요)"`.

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
- `durationMinutes`가 없으면 시작~종료 구간 길이(분, 30초 이상 올림)로 계산한다.

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
- 날짜별 1건: 수면 로그는 하루(사용자·날짜)에 1개이며, 같은 날 더 긴 수면이 오면 갱신한다(구간도 새 값으로 교체).
  - 새 수면 시간·점수로 다시 계산한 XP에서 이미 지급한 XP를 뺀 차액만 지급한다(지급한 XP는 회수하지 않음).
  - `RECOVERY +1`, `INT +1`은 그날 로그에 처음 XP가 지급될 때 1회. 점수가 80 미만→80 이상으로 처음 바뀔 때 `INT +1`을 1회 추가.
- 날짜 귀속(#107): 수면은 깬 시각(`endedAt`)의 서버 현지 날짜(`app.timezone`)에만 반영한다.
  요청 `date`가 그 날짜와 다르면 해당 항목을 건너뛴다(`"수면은 깬 날짜(yyyy-MM-dd)에만 반영됩니다."`).
  같은 수면을 요청 `date`만 바꿔 여러 날짜로 받을 수 없다.
  - 수면 시간이 같거나 짧으면 변화 없음(중복 응답). 늘었지만 차액이 0이면 갱신하고 0 XP로 응답한다.
  - 한 요청에 SLEEP이 여러 개면 수면 시간이 가장 큰 값 하나만 반영한다.
  - 수면은 겹침 검사 대상이 아니다(낮잠이 포함된 조회 범위가 다음 날 기록과 겹칠 수 있음).

---

## 3. goals (오늘의 목표 달성 현황)

동기화 응답(`/api/health-activities/sync`)에 `goals` 배열을 추가한다.
현재값은 해당 사용자의 그날(요청 `date`) 저장된 활동 기록 누적값으로 계산한다.

| type | target | 단위 | current 산출 |
|------|--------|------|-------------|
| STEPS   | 8000 | steps   | 그날 STEPS 로그의 걸음 수(하루 1개, 최댓값) |
| WORKOUT | 30   | minutes | 그날 시작한 WORKOUT 활동 시간(분) 합 |
| SLEEP   | 420  | minutes | 그날 SLEEP 로그의 수면 시간(분, 하루 1개, 최댓값) |

응답 형태:

```json
"goals": [
  { "type": "STEPS",   "target": 8000, "current": 8500, "unit": "steps",   "achieved": true },
  { "type": "WORKOUT", "target": 30,   "current": 45,   "unit": "minutes", "achieved": true },
  { "type": "SLEEP",   "target": 420,  "current": 450,  "unit": "minutes", "achieved": true }
]
```

- `achieved = current >= target`.
- WORKOUT 로그의 날짜는 요청 `date`가 아니라 운동 시작 시각의 서버 현지 날짜다. 따라서 다른 날 시작한 운동은 그 날의 goals에 집계된다.
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

---

## 5. 동기화 검증·상한

### 날짜 창 (서버 시간대 `app.timezone`, 기본 Asia/Seoul)

- 요청 `date`는 서버 기준 오늘 ±1일 이내여야 한다.
- 활동 `startedAt`은 `date` 전날 00:00 이후, `endedAt`은 `date` 다음 날 24:00 이전이어야 한다.
- `startedAt`/`endedAt`은 현재 시각 + 5분(시계 오차 허용)을 넘을 수 없다.

### 값 상한

| 항목 | 규칙 |
|------|------|
| 걸음 수 | 0 ~ 100,000 |
| 수면 시간 | 0 ~ 960분(16시간), 구간 길이(분, 반올림) 이하 |
| 운동 구간 | 시작 < 종료, 최대 720분(12시간) |
| 운동 시간 | 0 ~ 720분, 구간 길이(분, 반올림) 이하 |
| 칼로리 | 0 ~ 10,000kcal, 그리고 분당 20kcal × 구간 길이 이하 |
| 거리 | 0 ~ 500,000m |
| 수면 점수 | 0 ~ 100 |
| 요청당 활동 수 | 최대 50개 |

- 구간 길이(분)는 `(구간 초 + 30) / 60`(30초 이상 올림)이다. 허용 오차(+1분)는 없다(#107).
  클라이언트는 활동 시간(구간 이하)을 같은 방식으로 반올림해 보내므로 정상 기록은 항상 통과한다. 1초 구간은 0분이다.

### 거부(400)와 건너뛰기의 구분

- **요청 전체 400** (구조 오류): 필수 필드 누락, 종료 < 시작, 활동 50개 초과, 요청 `date`가 오늘 ±1일 밖.
- **해당 항목만 건너뛰기** (값 범위 위반): 상한 초과, 분 값이 구간보다 김, 미래 시각, 구간이 날짜 창 밖,
  길이 0인 운동 구간, 분당 칼로리 초과, 미래 날짜 걸음 수, 깬 날짜가 요청 `date`와 다른 수면. 응답은 `duplicate=true`, 0 XP,
  메시지 `"<source> 기록을 반영하지 않았어요: <사유>"`. 나머지 활동은 정상 반영한다.

### 겹침 (WORKOUT만)

- 같은 사용자의 WORKOUT끼리 시간 구간이 겹치면(경계만 맞닿는 경우 제외) 나중 항목을 건너뛴다.
  저장된 기록과 요청 내부 모두 대상이며, 요청 내부에서는 먼저 온 것만 반영한다.
- 응답은 `duplicate=true`, 0 XP, 메시지 `"<source> 겹치는 기록이 이미 반영됨"`.
- 같은 활동 재전송(시작·종료·종목이 같음)은 겹침이 아니라 중복(`"<source> 이미 반영됨"`)으로 처리한다.

### 일일 XP 상한

사용자·날짜별 지급 XP 합계 상한:

| 범위 | 하루 상한 | 비고 |
|------|-----------|------|
| 전체 | 600 | 걸음 100 + 수면 80 + 운동 400 = 580을 반올림 |
| STEPS | 100 | 활동별 상한(날짜별 로그 1건) |
| SLEEP | 80 | 활동별 상한(날짜별 로그 1건) |
| WORKOUT | 400 | 그날 WORKOUT 로그 지급 XP 합계(최대치 운동 2회분) |

- 정상 동기화의 하루 최대는 100 + 80 + 400 = 580이며, 총 상한 600은 방어용으로 유지한다.
- 지급액은 `min(획득 XP, 전체 남은 한도, 유형 남은 한도)`이다.
- WORKOUT은 운동 시작 시각의 서버 현지 날짜(`app.timezone`) 기준으로 상한을 계산하고 그 날짜로 기록한다.
  같은 날 운동을 요청 `date`만 바꿔 나눠 보내도 400을 넘지 않는다. STEPS·SLEEP은 요청 `date` 기준이다.
- 상한을 넘는 몫은 잘라서 지급하고, 결과 메시지에 `(일일 XP 상한 도달)`을 붙인다(전체·유형 상한 공통). 로그에는 실제 지급액을 기록한다.
- 잘린 몫(걸음·수면 차액 포함)은 이후 재동기화에서 남은 상한 안에서만 지급될 수 있다.
- 스탯은 그 항목에 실제로 지급된 XP가 0보다 클 때만 반영한다(상한 소진 후 스탯만 쌓이지 않게, #107). goals는 상한과 무관하게 반영한다.
- 같은 사용자의 동시 동기화는 캐릭터 행 잠금으로 직렬화한 뒤 남은 상한을 계산한다.
- 처음 보는 사용자는 동기화 트랜잭션을 시작하기 전에 짧은 별도 트랜잭션에서 캐릭터를 만든다(요청당 DB 연결은 한 번에 하나). 동시 요청이 함께 만들려 해 유일 제약에 걸리면
  무시하고 이미 만들어진 행을 잠가 쓴다(두 요청 모두 성공, 캐릭터 1개).

### 구 형식 수면 키 정리 (Flyway V2, 1회, 멱등)

- 수면 로그 키는 `userId|date|SLEEP`이다. 예전 `userId|start|end|SLEEP` 로그가 남은 날은 Flyway 마이그레이션
  `V2__sleep_key_normalization`이 사용자·날짜별로 수면 시간이 가장 긴 행(동률이면 먼저 생성된 행)을
  새 키로 바꾸고, 나머지는 `<기존키>|legacy|<id>`로 바꿔 보존한다. 새 키가 이미 있는 날은 구 형식 행을 모두 legacy로 바꾼다.
- 행은 삭제하지 않는다. legacy 행의 지급 XP는 같은 날 새 키 행의 `gainedXp`로 옮기고 legacy 행은 0으로 둔다(#66).
  그날 지급 XP 합계는 바뀌지 않으며, 이후 더 긴 수면이 오면 legacy 지급분까지 포함한 금액과의 차액만 지급한다(이중 지급 없음).
  새 키 행이 없는 날의 legacy XP는 그대로 둔다.
- Flyway가 한 번만 실행한다(기동마다 전체 스캔하지 않음). 그사이 새 키가 생겨 유일 제약에 걸린 그룹은 그 그룹만 되돌리고
  구 형식 행을 모두 legacy로 바꾼다. 그 밖의 오류는 마이그레이션 전체를 되돌리고 기동을 멈춘다(다음 기동 때 다시 시도).

### 요청 빈도 제한 (레이트 리밋, #48)

| 대상 | 키 | 기본 한도 | 설정 키 |
|---|---|---|---|
| `POST /api/health-activities/sync` | 인증 주체(userId) | 분당 30회 | `app.rate-limit.sync-per-minute` |
| `/api/auth/**` 전체(소셜 로그인 포함) | 클라이언트 IP | 분당 30회 | `app.rate-limit.auth-per-minute` |
| `/api/auth/login`, `/api/auth/signup` | 클라이언트 IP | 분당 10회(위 한도와 함께) | `app.rate-limit.login-per-minute` |

- 1분 고정 윈도우(첫 요청 시각부터 1분), 단일 인스턴스 인메모리. 한도 0 이하면 해당 제한을 끈다.
- 초과 시 `429`, `Retry-After: <윈도우가 끝날 때까지 남은 초(올림, 최소 1)>`, 오류 body 형식은 다른 오류와 같다
  (`error.rate-limited`: `"요청이 너무 많아요. N초 후에 다시 시도해 주세요."`).
- 클라이언트 IP는 연결 주소다. `app.rate-limit.trust-forwarded-header=true`일 때만 `X-Forwarded-For`의 마지막 줄의
  마지막 값을 쓰며, 64자를 넘거나 IP 리터럴이 아니면 연결 주소로 돌아간다. IPv6는 /64 접두사 단위로 센다.
- 메모리의 키 수는 `app.rate-limit.max-keys`(기본 100000)로 제한한다. 가득 차면 만료 키를 먼저 지우고, 그래도 부족하면
  가장 오래된 윈도우를 내보낸다(새 클라이언트를 거부하지 않는다).
- CORS preflight(OPTIONS)와 인증 실패(401)한 동기화 요청은 세지 않는다.
- 아이디 로그인은 같은 (아이디, IP)로 5회 실패하면 그 조합이 15분 잠기고(`app.auth.max-failed-logins`), IP와 무관하게
  한 아이디에 50회 실패가 쌓이면 아이디 전체가 15분 잠긴다(`app.auth.max-failed-logins-per-username`, `app.auth.lockout-minutes`).
  시도는 비밀번호 비교 전에 먼저 세고, 성공하면 그 (아이디, IP) 기록을 지운다. 잠금 중 응답은 일반 로그인 실패(400)와 같다.

### 인증 세션 (Auth v2, #55·#64·#76)

- 액세스 토큰(JWT, HS256) 기본 1시간(`auth.jwt.ttl`), 리프레시 토큰 기본 30일(`auth.refresh.ttl`, 불투명 43자, DB에는 SHA-256 hex만).
- 로그인·가입·소셜 교환 응답: 기존 필드(`userId`, `displayName`, `accessToken`, `tokenType`, `expiresIn`, 아이디 로그인은 `userIdentifier`)
  + `refreshToken`, `refreshExpiresIn`(초).
- `POST /api/auth/refresh {refreshToken}`: 유효하면 기존 토큰 폐기·`replaced_by` 기록 후 같은 family로 새 리프레시와 새 액세스 토큰.
  새 리프레시 만료 = min(지금 + 30일, family 생성 + 90일 `auth.refresh.absolute-ttl`).
  회전된 지 30초(`auth.refresh.reuse-grace`) 안의 토큰이 다시 오면 같은 family에 형제 토큰을 발급(기존 후계 유지, 200).
  유예 구간이 지난 회전된 토큰이면 family 전체 폐기 + 사용자 액세스 토큰 무효화 후 401. 로그아웃으로 폐기된 토큰·없음·만료는 401.
  공개 경로이며 `/api/auth/**` IP 한도를 받는다.
- `POST /api/auth/logout {refreshToken}`: 공개 경로(토큰 소유가 권한). 그 토큰의 family 폐기. 없는·폐기된 토큰도 204, 빈 값 400.
- `POST /api/auth/logout-all`(Bearer): 요청자의 모든 리프레시 폐기 + `tokens_valid_after` 갱신. 204.
- 액세스 토큰은 `iat < tokens_valid_after`(사용자별, 초 단위)면 scope와 무관하게 401(`auth.session-revoked`). 서버 메모리 캐시 30초.
- `POST /api/auth/watch-token`: `{accessToken, tokenType, expiresIn}`, `scope: "read"`, 7일(`auth.jwt.watch-ttl`).
  read 토큰은 `GET /api/characters/{userId|me}`, `GET /api/health-activities`만 허용, 나머지는 403(`auth.read-only-token`).
  scope 클레임이 없는 토큰은 전체 권한, 모르는 scope 값은 401.
- 정리 작업: 하루 1회 만료 행과 폐기 후 7일 지난 행 삭제.
- 공개 경로: `signup`, `login`, `{google|kakao|naver|apple}/native`, `refresh`, `logout`, `{kakao|naver|google}`(웹 코드 교환).
  그 밖의 `/api/**`는 Bearer 필요(지원하지 않는 `/api/auth/{x}`도 401).
- 외부 ID 토큰 JWKS: 조회 실패 시 마지막 키를 최대 24시간(`jwks-stale-max`) 사용, 넘으면 503.

### 오류 응답 문구

- 사용자에게 보여 주려고 던진 오류(400 검증, 401, 403, 429 레이트 리밋, 503 설정·외부 인증 장애)만 번역된 메시지를 그대로 응답한다.
  설정 누락 문구에는 환경변수 이름이나 provider 이름을 넣지 않는다(`"이 로그인 방식은 지금 사용할 수 없어요."`).
- 서버 로그: 4xx는 warn 한 줄(스택 없음), 5xx는 error와 스택.
- 그 밖의 예외는 상태 코드(잘못된 인자 400, 잘못된 상태 503, 그 외 500)를 유지하고 메시지는
  `"요청을 처리하지 못했어요."` / `"We couldn’t process your request."`로 응답한다. 상세는 서버 로그에만 남긴다.
