# 백엔드 계약 픽스처

iOS가 주고받는 JSON을 백엔드 record와 맞춰 두는 파일이다. 파일 하나가 백엔드 타입 하나에 대응하고,
파일 이름은 그 record 이름과 같다. `ContractAndMappingTests.swift`(`BackendContractTests`)가 테스트 번들에서
읽어 iOS 모델로 디코딩·비교하고, 다른 네트워크 테스트도 `BackendFixture`로 이 파일을 바탕으로 응답을 만든다.

| 파일 | 백엔드 타입 | 엔드포인트 | iOS 타입 |
|---|---|---|---|
| `HealthActivitySyncResponse.json` | `com.wellnessgame.api.HealthActivitySyncResponse` | `POST /api/health-activities/sync` | `HealthActivitySyncResponse` |
| `HealthActivitySyncRequest.json` | `com.wellnessgame.api.HealthActivitySyncRequest` (`ActivityPayload`) | `POST /api/health-activities/sync` 요청 | `HealthActivitySyncRequest` |
| `CharacterSummaryResponse.json` | `com.wellnessgame.api.CharacterSummaryResponse` | `GET /api/characters/me` | `CharacterSummaryResponse` |
| `DailyActivitiesResponse.json` | `com.wellnessgame.api.DailyActivitiesResponse` | `GET /api/health-activities?date=` | `DailyActivitiesResponse` |
| `PasswordAuthResponse.json` | `com.wellnessgame.api.AuthController.PasswordAuthResponse` | `POST /api/auth/login`, `/signup` | `AuthTokenResponse` |
| `SocialLoginResponse.json` | `com.wellnessgame.api.AuthController.SocialLoginResponse` | `POST /api/auth/{provider}/native` | `AuthTokenResponse` |
| `RefreshResponse.json` | `com.wellnessgame.api.AuthController.RefreshResponse` | `POST /api/auth/refresh` | `RefreshResponse` |
| `IssuedToken.json` | `com.wellnessgame.auth.IssuedToken` | `POST /api/auth/watch-token` | `WatchTokenResponse` |
| `ErrorResponse.json` | `com.wellnessgame.api.ApiExceptionHandler#error` (Map 본문) | 모든 오류 응답 | `NetworkClient`의 `ServerError` |

- 백엔드 테스트가 실제 record를 직렬화해 이 파일을 다시 만들 수 있도록 이름·필드 순서는 record 정의를 따른다.
  값은 예시이며, iOS 테스트는 필드 이름·타입·null 처리에 의존한다.
- 파일을 추가하면 Xcode 프로젝트의 `WellnessGameTests` Resources 단계에도 넣는다(번들에 평평하게 복사된다).
