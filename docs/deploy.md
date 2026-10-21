# 배포 파이프라인

`main`에 머지되면 `.github/workflows/deploy.yml`이 **바뀐 대상만** 골라 검증한 뒤 배포합니다.
Render·Vercel의 자체 자동 배포는 꺼 두어 한 번의 머지가 두 번 배포되지 않게 합니다.

## 흐름

```mermaid
flowchart LR
  A[main push] --> B[changes<br/>변경 감지]
  B -->|backend| C[verify-backend<br/>backend-ci.yml 호출]
  B -->|hono| D[verify-hono<br/>hono-ci.yml 호출]
  B -->|web| E[verify-web<br/>web-ci.yml 호출]
  C -->|성공| F[deploy-backend<br/>Render 훅 → live 대기 → /healthz]
  D -->|성공| G[deploy-hono<br/>Render 훅 → live 대기 → /healthz]
  E -->|성공| H[deploy-web<br/>Vercel prebuilt → / · /privacy]
  F --> R[report<br/>작업 요약]
  G --> R
  H --> R
```

| 대상 | 경로 기준(변경 감지) | 서비스 | 배포 방식 | 주소 |
|---|---|---|---|---|
| backend | `backend/**` | Render `wellness-game-api` (`srv-dbac3sijnfac73f5d1ng`) | 배포 훅 POST → 최신 배포가 live 될 때까지 대기 → `/healthz` | https://wellness-game-api.onrender.com |
| hono | `hono-server/**`, `backend/db/schema.sql`, `backend/src/main/resources/messages*.properties` | Render `wellness-game-hono` (`srv-dbc5oee1egvs739k01ag`) | 배포 훅 POST → live 대기 → `/healthz` | https://wellness-game-hono.onrender.com |
| web | `web/**` | Vercel `wellness-game` (Root Directory `web`) | `vercel pull` → `vercel build --prod` → `vercel deploy --prebuilt --prod` → `/`, `/privacy` 확인 | https://wellness-game-kohl.vercel.app |

- 검증은 기존 CI 워크플로(`backend-ci.yml`, `hono-ci.yml`, `web-ci.yml`)를 `workflow_call`로 호출해 같은 테스트를 돌립니다. PR에서 이미 검증한 `main`을 다시 push 트리거로 돌리지 않습니다.
- 배포 잡은 검증이 성공한 대상만 실행합니다. 모든 배포는 `production` 환경을 쓰고, 한 번에 하나만 진행합니다(`deploy-production` 동시성 그룹, 취소하지 않음).
- 직전 커밋(`github.event.before`)을 알 수 없으면(새 브랜치 푸시 등) 전체를 배포합니다.

## 시크릿과 변수

GitHub 저장소 **Settings › Secrets and variables › Actions**에서 설정하거나 `gh` CLI로 설정합니다. 값은 로그에 나오지 않습니다.
시크릿 값은 명령행 인자로 넘기지 말고 `gh secret set <이름>`을 실행한 뒤 입력창에 붙여넣습니다(셸 기록에 남지 않게).

| 이름 | 종류 | 필수 | 값을 얻는 곳 |
|---|---|---|---|
| `RENDER_DEPLOY_HOOK_API` | 시크릿 | 필수 | Render 대시보드 › `wellness-game-api` › **Settings › Deploy Hook** 의 URL |
| `RENDER_DEPLOY_HOOK_HONO` | 시크릿 | 필수 | Render 대시보드 › `wellness-game-hono` › **Settings › Deploy Hook** 의 URL |
| `RENDER_API_KEY` | 시크릿 | 선택 | Render 대시보드 › **Account Settings › API Keys**에서 생성. 있으면 배포 완료(live)를 기다리고 실패를 감지합니다. 없으면 헬스체크만 합니다 |
| `VERCEL_TOKEN` | 시크릿 | 필수 | Vercel 대시보드 › **Account Settings › Tokens**에서 생성. 범위는 팀 `team_4EowikoAvTyqEpFshzZSpNmm`이 보이도록 설정 |
| `RENDER_SERVICE_ID_API` | 변수 | `RENDER_API_KEY`를 쓸 때 필수 | `srv-dbac3sijnfac73f5d1ng` (서비스 URL의 `srv-…` 부분) |
| `RENDER_SERVICE_ID_HONO` | 변수 | `RENDER_API_KEY`를 쓸 때 필수 | `srv-dbc5oee1egvs739k01ag` |
| `VERCEL_ORG_ID` | 변수 | 필수 | `team_4EowikoAvTyqEpFshzZSpNmm` (Vercel 팀 설정 › General) |
| `VERCEL_PROJECT_ID` | 변수 | 필수 | `prj_tMeXGM2Ebbobapt8wvqah5GtuKBW` (Vercel 프로젝트 › Settings › General) |

설정 명령 (저장소 루트에서 실행):

```bash
# 시크릿: 실행하면 입력창이 뜨면 값을 붙여넣는다
gh secret set RENDER_DEPLOY_HOOK_API --repo seongkyu-lim/wellness-game
gh secret set RENDER_DEPLOY_HOOK_HONO --repo seongkyu-lim/wellness-game
gh secret set VERCEL_TOKEN --repo seongkyu-lim/wellness-game
gh secret set RENDER_API_KEY --repo seongkyu-lim/wellness-game   # 선택

# 변수
gh variable set RENDER_SERVICE_ID_API --repo seongkyu-lim/wellness-game --body '<srv-… (API 서비스 ID)>'
gh variable set RENDER_SERVICE_ID_HONO --repo seongkyu-lim/wellness-game --body '<srv-… (Hono 서비스 ID)>'
gh variable set VERCEL_ORG_ID --repo seongkyu-lim/wellness-game --body '<team_… (Vercel 팀 ID)>'
gh variable set VERCEL_PROJECT_ID --repo seongkyu-lim/wellness-game --body '<prj_… (Vercel 프로젝트 ID)>'

# 배포 환경(production). 필요하면 보호 규칙(수동 승인, main 만 배포)을 웹에서 추가한다
gh api -X PUT repos/seongkyu-lim/wellness-game/environments/production
```

누락된 시크릿이나 변수가 있으면 해당 배포 잡의 첫 단계가 어떤 이름을 추가해야 하는지 알려 주고 실패합니다.

## 한 번만 하는 대시보드 작업

1. **Render (두 서비스 모두):** 서비스 › **Settings › Build & Deploy › Auto-Deploy** 를 **Off**로 바꿉니다.
   두 서비스는 Blueprint(`render.yaml`)로 만들지 않았으므로 `render.yaml`의 `autoDeploy: false`만으로는 꺼지지 않습니다.
2. **Render 배포 훅:** 서비스 › **Settings › Deploy Hook**에서 URL을 복사해 위 시크릿에 넣습니다. 훅은 `main` 최신 커밋을 배포합니다.
3. **Vercel:** Root Directory가 `web`인지 확인합니다. 운영 `main` 배포는 `web/vercel.json`의 `git.deploymentEnabled.main: false`로 Git 연동에서 끕니다. PR 프리뷰는 그대로 유지됩니다. Production Branch는 `main`으로 둡니다.
4. **GitHub `production` 환경:** 필요하면 **Settings › Environments › production**에서 Required reviewers를 추가해 배포 전 수동 승인을 받습니다.

## 수동 재배포

- 웹: **Actions › Deploy › Run workflow**, Branch는 `main`, `targets`에서 `all`, `backend`, `hono`, `web` 중 하나를 고릅니다. 변경 감지 없이 선택한 대상을 전부 검증하고 배포합니다.
- CLI: `gh workflow run deploy.yml --ref main -f targets=backend`
- `main`이 아닌 브랜치에서 실행하면 변경 감지 단계에서 실패합니다.

## 롤백

- **Render (backend, hono):** 서비스 › **Deploys**에서 직전에 live였던 배포를 골라 **Rollback**을 누릅니다. 이전 빌드를 다시 배포하며, 이 작업은 Actions를 거치지 않습니다.
- **Vercel (web):** 프로젝트 › **Deployments**에서 직전 운영 배포를 열고 **Promote to Production**(또는 `vercel rollback <배포 URL>`)을 실행합니다.
- **코드로 되돌리기:** 문제 커밋을 `dev`에서 되돌리는 PR을 `main`에 머지하면 배포 파이프라인이 다시 배포합니다. 이 방법은 기록이 남고 검증도 거칩니다.
- **주의:** Flyway 마이그레이션(`backend/src/main/resources/db/migration`)은 롤백되지 않습니다. 스키마를 바꾼 배포는 이전 버전 코드와 호환되는지 먼저 확인합니다.

## 문제 해결

- `시크릿 누락`, `변수 누락`: 위 표의 이름을 확인해 추가한 뒤 실패한 실행을 다시 실행합니다.
- `Render 배포 실패`: Render 대시보드의 Deploys 탭에서 빌드 로그를 확인합니다.
- `20분 안에 배포가 live 가 되지 않았습니다`: Render 쪽 빌드가 오래 걸리는 경우입니다. 대시보드에서 진행 상황을 본 뒤 수동 재배포합니다.
- `healthz 가 응답하지 않습니다`: 무료 플랜 콜드 스타트는 보통 1분 안에 깨어납니다. 계속 실패하면 Render 로그를 봅니다.
- Vercel 빌드 실패: `VITE_API_BASE_URL`, `VITE_PRIVACY_CONTACT_EMAIL`, `VITE_PRIVACY_EFFECTIVE_DATE` 같은 Vercel 환경변수가 Production에 있는지 확인합니다(`web/README.md` 참고).

## 알려진 위험

- **변경 감지 기준:** 배포 대상 판단은 `github.event.before`(직전 push)를 씁니다. 같은 `production` 그룹에서 대기 중인 실행은 하나만 남고 나머지는 취소되므로, 취소된 실행의 변경은 다음 실행의 diff에 나타나지 않을 수 있습니다. 배포가 빠졌다고 의심되면 `targets=all`로 수동 실행합니다.
- **검증 취소 정책:** 검증 워크플로는 `main`에서 취소하지 않습니다. 취소하면 위와 같은 누락이 생기기 때문입니다. 대신 PR 실행은 기존처럼 같은 PR의 이전 실행을 취소합니다.
- **Vercel 모노레포:** `vercel pull`, `vercel build`, `vercel deploy`를 저장소 루트에서 실행합니다(Root Directory `web`은 프로젝트 설정으로 적용). 첫 실행 로그에서 `web/` 기준으로 빌드되는지 확인합니다.
- **Vercel CLI 버전:** `deploy.yml`의 `VERCEL_CLI_VERSION`으로 고정합니다(현재 `62.5.0`). 올릴 때는 npm 게시 후 2주 이상 지난 버전을 고릅니다.
- **무료 플랜:** 두 Render 서비스는 free이며 잠들 수 있습니다. 출시 전에는 괜찮지만 운영 전에는 `starter` 이상을 고려합니다(`render.yaml` 주석 참고).

## 관련 파일

- `.github/workflows/deploy.yml`: 변경 감지, 검증 호출, 배포, 결과 요약
- `.github/workflows/backend-ci.yml`, `hono-ci.yml`, `web-ci.yml`: PR 검증과 `workflow_call`
- `render.yaml`: 서비스 정의(`autoDeploy: false`)
- `web/vercel.json`: 빌드 설정과 `main` Git 배포 끄기
