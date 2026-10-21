import { vi } from 'vitest'
import { character, tokenResponse, USER_ID } from './fixtures'
import type { ActivityEntry, Character } from '../api/types'

/**
 * 네트워크 경계(global fetch)를 대신하는 가짜 백엔드. 실제 서버 규칙을 따른다.
 * - refresh는 리프레시 토큰을 회전한다. 이미 회전된 토큰이 다시 오면 재사용으로 보고 family 전체를 폐기한다
 *   (그 뒤 그 로그인의 refresh·조회는 모두 401).
 * - logout은 family를 폐기하므로 액세스 토큰도 더는 통하지 않는다.
 * - /characters/me는 캐릭터가 없으면 본문 없는 404.
 * 모든 요청을 기록하고, 테스트는 `routes`의 처리기를 바꿔 실패 상황을 만든다.
 */

interface RecordedRequest {
  method: string
  /** 오리진을 뺀 경로+쿼리. 예: /api/health-activities?userId=...&date=... */
  path: string
  headers: Record<string, string>
  body: unknown
}

type Handler = (req: RecordedRequest) => Response | Promise<Response>

export function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const unauthorized = () => json(401, { message: '인증이 필요합니다.' })

export function createFakeBackend() {
  const state = {
    /** 현재 유효한 토큰 번호(acc-N·ref-N) */
    generation: 1,
    /** 재사용 감지·로그아웃으로 family가 폐기됐는지 */
    familyRevoked: false,
    /** 서버가 따로 무효화한 액세스 토큰 */
    revokedAccess: new Set<string>(),
    character: character() as Character | null,
    activities: [] as ActivityEntry[],
  }
  const requests: RecordedRequest[] = []

  const accessDenied = (req: RecordedRequest): boolean => {
    const token = req.headers.Authorization?.replace(/^Bearer /, '')
    return state.familyRevoked || token !== `acc-${state.generation}` || state.revokedAccess.has(token)
  }

  const routes: Record<string, Handler> = {
    'POST /api/auth/kakao': () => {
      // 새 로그인은 새 family를 시작한다.
      state.familyRevoked = false
      return json(200, tokenResponse(state.generation, 3600, { displayName: '홍길동' }))
    },
    'POST /api/auth/refresh': (req) => {
      const { refreshToken } = req.body as { refreshToken: string }
      if (state.familyRevoked) {
        return json(401, { message: '리프레시 토큰이 만료되었습니다.' })
      }
      if (refreshToken !== `ref-${state.generation}`) {
        state.familyRevoked = true
        return json(401, { message: '리프레시 토큰이 재사용되었습니다.' })
      }
      state.generation++
      return json(200, tokenResponse(state.generation))
    },
    'POST /api/auth/logout': () => {
      state.familyRevoked = true
      return new Response(null, { status: 204 })
    },
    'GET /api/characters/me': (req) => {
      if (accessDenied(req)) {
        return unauthorized()
      }
      return state.character ? json(200, { userId: USER_ID, character: state.character }) : new Response(null, { status: 404 })
    },
    'GET /api/health-activities': (req) => {
      if (accessDenied(req)) {
        return unauthorized()
      }
      const date = new URL(req.path, 'http://x').searchParams.get('date')
      return json(200, { userId: USER_ID, date, activities: state.activities })
    },
  }

  const fetchMock = vi.fn(async (input: string, init: RequestInit = {}) => {
    const url = new URL(input)
    const method = (init.method ?? 'GET').toUpperCase()
    const req: RecordedRequest = {
      method,
      path: `${url.pathname}${url.search}`,
      headers: { ...(init.headers as Record<string, string> | undefined) },
      body: typeof init.body === 'string' ? JSON.parse(init.body) : undefined,
    }
    requests.push(req)
    const handler = routes[`${method} ${url.pathname}`]
    return handler ? handler(req) : json(404, { message: `no route: ${method} ${url.pathname}` })
  })

  const server = {
    state,
    routes,
    requests,
    /** 경로가 prefix로 시작하는 요청만 */
    requestsTo: (prefix: string) => requests.filter((r) => r.path.startsWith(prefix)),
    /** 현재 액세스 토큰만 서버에서 무효화한다(리프레시 토큰은 유효). 다음 조회는 401 → refresh로 복구해야 한다. */
    revokeAccess() {
      state.revokedAccess.add(`acc-${state.generation}`)
    },
  }
  vi.stubGlobal('fetch', fetchMock)
  return server
}

export type FakeBackend = ReturnType<typeof createFakeBackend>

/** 새로고침 직후처럼 저장소에 Auth v2 세션(ref-1)과 이 탭의 액세스 토큰 사본(acc-1, 1시간 유효)을 넣어 둔다. */
export function storeV2Session(now: number) {
  localStorage.setItem(
    'wellness.session',
    JSON.stringify({ provider: 'kakao', userId: USER_ID, displayName: '홍길동', refreshToken: 'ref-1', refreshExpiresAt: now + 2_592_000_000 }),
  )
  sessionStorage.setItem('wellness.access', JSON.stringify({ userId: USER_ID, accessToken: 'acc-1', expiresAt: now + 3_600_000 }))
}
