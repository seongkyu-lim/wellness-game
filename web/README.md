# Wellness Game Web

iOS 앱과 동일한 백엔드·동일한 캐릭터를 사용하는 웹 대시보드입니다.

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

- 웹과 iOS 앱은 같은 REST API(`/api/health-activities/sync`, `/api/characters/{userId}`)를 사용합니다.
- 사용자 식별은 양쪽 모두 `userId` 문자열 하나로 이루어집니다.
  - 게스트: `guest:{uuid}` — 웹은 localStorage(`wellness.userId`), iOS는 UserDefaults에 보존
  - 소셜 로그인: `{provider}:{식별자}` (예: `kakao:12345`)
- 캐릭터 상태는 서버(DB)에만 존재하므로, **같은 userId를 쓰면 앱과 웹이 같은 캐릭터를 공유**합니다.
- iOS는 HealthKit에서 자동으로 데이터를 수집하고, 웹은 수동 입력으로 활동을 기록합니다.
  중복 반영은 서버의 externalKey 규칙으로 방지됩니다.
- 프로덕션 배포 시 백엔드 `CORS_ALLOWED_ORIGINS` 환경변수에 웹 오리진을 추가해야 합니다.

## 빌드

```bash
npm run build   # 타입 체크 + dist/ 생성
```
