# 백엔드 응답 문구 용어집 (ko / en)

서버가 `Accept-Language` 에 따라 만드는 사용자 노출 문구의 용어 기준이다. 헤더가 없거나 ko·en 이 아니면 ko 로 응답한다.
문구 원본은 `src/main/resources/messages.properties`(ko, 기본) / `messages_en.properties`(en) 이다.
iOS 쪽 `ios/GLOSSARY.md` 가 생기면 그 용어를 우선하고 이 표와 properties 를 맞춘다.

## 운동 이름 (`WorkoutType` → `workout.name.*`)

| WorkoutType | ko | en |
|---|---|---|
| SWIMMING | 수영 | Swimming |
| RUNNING | 달리기 | Running |
| WALKING | 걷기 | Walking |
| CYCLING | 자전거 | Cycling |
| STRENGTH_TRAINING | 근력 운동 | Strength training |
| OTHER | 운동 | Workout |

## 게임·활동 용어

| ko | en | 쓰임 |
|---|---|---|
| 걸음 수 | steps | 걸음 보상, 값 범위 사유 |
| 걸음 수 보상 | Step reward | `activity.reward.steps` |
| 수면 회복 보너스 | Sleep recovery bonus | `activity.reward.sleep` |
| ~ 완료 | ~ complete | `activity.reward.workout` (예: Running complete +105 XP) |
| XP | XP | 번역하지 않음 |
| 일일 XP 상한 | daily XP cap | `activity.reward.capped` |
| 이미 반영됨 | already counted | 중복 기록 |
| 겹치는 기록 | overlaps a workout | 시간이 겹친 운동 |
| 기록을 반영하지 않았어요 | wasn’t counted | 값 범위 위반으로 건너뛴 항목 |
| 동기화 | sync | 동기화 요청·날짜 |
| 로그인 | sign in | 인증 문구 |
| 아이디 | username | 회원가입·로그인 |
| 토큰 만료 | session has expired | 401 문구 (사용자에게는 "세션"으로 표현) |

## 번역하지 않는 값

- 활동 결과 메시지 앞의 `source`(예: `STEPS`, `CYCLING`)는 API 코드 값이라 두 언어 모두 그대로 쓴다.
- 필드명(`startedAt`, `durationMinutes` 등)과 Bean Validation 응답의 `필드: 문구` 앞부분은 그대로 둔다.
- 응답의 `error` 필드(HTTP reason phrase)와 `goals[].unit`(`steps`/`minutes`)은 코드 값이다.

## 작성 규칙

- 영어 문구의 축약형·소유격은 ’(U+2019) 를 쓴다. 인자가 있는 문구에서 `'` 는 MessageFormat 인용 부호로 해석된다.
- 자릿수 구분 없이 보여야 하는 숫자는 `{0,number,#}`, 영어에서 구분이 자연스러운 값은 `{0,number,integer}` 를 쓴다.
- 새 키는 두 파일에 함께 추가한다(`MessagesPropertiesTest` 가 키 집합을 비교한다).
- 서버 로그 전용 문구와 설정 오류(기동 실패) 메시지는 다국어 대상이 아니다.
