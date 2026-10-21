import { vi } from 'vitest'
import type { TokenResponse } from '../api/authApi'
import type { ActivityEntry, Character } from '../api/types'

/**
 * 네트워크 경계(global fetch)를 대신하는 가짜 백엔드.
 * 실제 서버처럼 경로·메서드로 라우팅하고, 리프레시 토큰을 회전하며(이미 쓴 토큰은 401), 모든 요청을 기록한다.
 * 테스트는 `server.routes`의 처리기를 바꿔 실패 상황을 만든다.
 */

export interface RecordedRequest {
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

export const USER_ID = 'kakao:1'

export function tokenResponse(n: number, expiresIn = 3600): TokenResponse {
  return {
    userId: USER_ID,
    displayName: '홍길동',
    accessToken: `acc-${n}`,
    tokenType: 'Bearer',
    expiresIn,
    refreshToken: `ref-${n}`,
    refreshExpiresIn: 2_592_000,
  }
}

export const CHARACTER: Character = {
  level: 4,
  currentXp: 30,
  totalXp: 1_230,
  nextLevelXp: 120,
  stats: { str: 11, vit: 12, intStat: 13, discipline: 14, recovery: 15 },
}

export function stepsEntry(steps: number, gainedXp: number): ActivityEntry {
  return {
    type: 'STEPS',
    source: 'HEALTHKIT',
    durationMinutes: null,
    calories: null,
    distanceMeters: null,
    steps,
    sleepMinutes: null,
    sleepScore: null,
    gainedXp,
    startedAt: null,
    endedAt: null,
  }
}

export interface FakeBackendOptions {
  /** 서버가 현재 유효하다고 보는 토큰 번호(acc-N·ref-N). 기본 1 */
  tokenGeneration?: number
  character?: Character | null
  activities?: ActivityEntry[]
}

export function createFakeBackend(options: FakeBackendOptions = {}) {
  const state = {
    generation: options.tokenGeneration ?? 1,
    character: options.character === undefined ? CHARACTER : options.character,
    activities: options.activities ?? [],
  }
  const requests: RecordedRequest[] = []

  const requireBearer = (req: RecordedRequest): Response | null =>
    req.headers.Authorization === `Bearer acc-${state.generation}` ? null : json(401, { message: '인증이 필요합니다.' })

  const routes: Record<string, Handler> = {
    'POST /api/auth/refresh': (req) => {
      const { refreshToken } = req.body as { refreshToken: string }
      if (refreshToken !== `ref-${state.generation}`) {
        return json(401, { message: '리프레시 토큰이 만료되었습니다.' })
      }
      state.generation++
      return json(200, tokenResponse(state.generation))
    },
    'POST /api/auth/logout': () => new Response(null, { status: 204 }),
    'POST /api/auth/kakao': () => json(200, tokenResponse(state.generation)),
    'GET /api/characters/me': (req) =>
      requireBearer(req) ??
      (state.character ? json(200, { userId: USER_ID, character: state.character }) : json(404, {})),
    'GET /api/health-activities': (req) => {
      const denied = requireBearer(req)
      if (denied) {
        return denied
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

  return {
    state,
    routes,
    requests,
    fetchMock,
    /** 경로가 prefix로 시작하는 요청만 */
    requestsTo: (prefix: string) => requests.filter((r) => r.path.startsWith(prefix)),
    install() {
      vi.stubGlobal('fetch', fetchMock)
      return this
    },
  }
}

export type FakeBackend = ReturnType<typeof createFakeBackend>

/** 새로고침 직후처럼 저장소에 Auth v2 세션(+ 이 탭의 액세스 토큰 사본)을 넣어 둔다. */
export function storeV2Session(now: number, n = 1, { withAccess = true, accessExpiresIn = 3600 } = {}) {
  localStorage.setItem(
    'wellness.session',
    JSON.stringify({
      provider: 'kakao',
      userId: USER_ID,
      displayName: '홍길동',
      refreshToken: `ref-${n}`,
      refreshExpiresAt: now + 2_592_000_000,
    }),
  )
  if (withAccess) {
    sessionStorage.setItem(
      'wellness.access',
      JSON.stringify({ userId: USER_ID, accessToken: `acc-${n}`, expiresAt: now + accessExpiresIn * 1000 }),
    )
  }
}
