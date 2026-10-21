# WellnessGameTests

## 구성

- 테스트 번들은 호스트 앱(`TEST_HOST`) 없이 돈다. 앱·Watch·위젯의 테스트 대상 소스를 번들에 직접 컴파일한다
  (`project.pbxproj`의 WellnessGameTests Sources 단계). 그래서 Watch 로직도 이 번들에서 iPhone 시뮬레이터로 테스트한다.
- 호스트 앱이 없어 Keychain 권한이 없다(`errSecMissingEntitlement`, -34018). 실제 Keychain을 쓰는 `KeychainTokenStoreTests`는
  이 환경에서 건너뛰고, 호스트 앱을 붙이면 실행된다.
- 공용 테스트 더블·픽스처·시간 도우미는 `TestSupport.swift`, 백엔드 응답 픽스처는 `Fixtures/`(README 참고)에 있다.

## 커버리지 읽는 법

`WellnessGame` 스킴의 테스트 동작은 `WellnessGameTests.xctest`만 커버리지를 모은다(호스트 앱이 없으므로 앱 타깃은 실행되지 않는다).

```sh
xcodebuild test -project ios/WellnessGame.xcodeproj -scheme WellnessGame \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -resultBundlePath build/tests.xcresult
xcrun xccov view --report build/tests.xcresult
```

- 보고서의 `WellnessGameTests.xctest` 합계에는 테스트 파일 자신(`*Tests.swift`, `TestSupport.swift`)도 들어 있다.
  제품 코드 커버리지를 볼 때는 이 파일들을 빼고 `ios/WellnessGame/`, `ios/Shared/`, `ios/WellnessGameWatch*/` 파일만 합산한다.
- Swift 패키지(GoogleSignIn, KakaoSDK 등)가 함께 표시될 수 있으니 무시한다.
- 번들에 컴파일하지 않는 화면 파일(`ContentView`, `LoginSheetView`, `SocialLoginService` 등)은 보고서에 나오지 않는다.
