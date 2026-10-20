import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setLanguage } from '../i18n/core'
import { sessionManager } from '../lib/session'
import { ApiError, fetchDailyActivities, fetchMyCharacter, isUnauthorized, postSocialLogin } from './client'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

let fetchMock: ReturnType<typeof vi.fn>

/** 리프레시 토큰이 없는 이전 형식 세션(액세스 토큰만)으로 로그인한 상태를 만든다. */
function startLegacySession(accessToken = 'tok-123') {
  sessionManager.start('kakao', { userId: 'kakao:1', accessToken, tokenType: 'Bearer', expiresIn: 3600 })
}

beforeEach(() => {
  fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
  startLegacySession()
})
afterEach(() => setLanguage('ko'))

function lastHeaders(): Record<string, string> {
  const init = fetchMock.mock.calls.at(-1)?.[1] as RequestInit
  return init.headers as Record<string, string>
}

describe('요청 헤더', () => {
  it('보호 API에 Accept-Language와 Authorization Bearer를 붙인다', async () => {
    setLanguage('en')
    fetchMock.mockResolvedValue(jsonResponse(200, { userId: 'kakao:1', character: {} }))
    await fetchMyCharacter()
    expect(fetchMock.mock.calls[0][0]).toMatch(/\/api\/characters\/me$/)
    expect(lastHeaders()).toEqual({ 'Accept-Language': 'en', Authorization: 'Bearer tok-123' })
  })

  it('Accept-Language는 요청 시점의 현재 언어를 따른다', async () => {
    fetchMock.mockImplementation(async () => jsonResponse(200, { userId: 'u', date: '2026-01-01', activities: [] }))
    setLanguage('ko')
    await fetchDailyActivities('kakao:1', '2026-01-01')
    expect(lastHeaders()['Accept-Language']).toBe('ko')
    setLanguage('en')
    await fetchDailyActivities('kakao:1', '2026-01-01')
    expect(lastHeaders()['Accept-Language']).toBe('en')
    expect(fetchMock.mock.calls[1][0]).toContain('/api/health-activities?userId=kakao%3A1&date=2026-01-01')
  })

  it('로그인 교환 요청에는 Authorization 없이 Accept-Language와 JSON 본문을 보낸다', async () => {
    setLanguage('en')
    fetchMock.mockResolvedValue(
      jsonResponse(200, { userId: 'google:1', displayName: null, accessToken: 'a', tokenType: 'Bearer', expiresIn: 60 }),
    )
    await postSocialLogin('google', 'code-1', 'http://localhost:5173/')
    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(init.method).toBe('POST')
    expect(lastHeaders()).toEqual({ 'Accept-Language': 'en', 'Content-Type': 'application/json' })
    expect(JSON.parse(init.body as string)).toEqual({ code: 'code-1', redirectUri: 'http://localhost:5173/' })
  })
  it('PKCE codeVerifier가 있으면 교환 본문에 함께 보낸다', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(200, { userId: 'google:1', displayName: null, accessToken: 'a', tokenType: 'Bearer', expiresIn: 60 }),
    )
    await postSocialLogin('google', 'code-1', 'http://localhost:5173/', 'v'.repeat(43))
    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(JSON.parse(init.body as string)).toEqual({
      code: 'code-1',
      redirectUri: 'http://localhost:5173/',
      codeVerifier: 'v'.repeat(43),
    })
  })
})

describe('오류 처리', () => {
  it('401은 isUnauthorized로 판별된다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(401, { message: '인증이 필요합니다.' }))
    const error = await fetchMyCharacter().catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(isUnauthorized(error)).toBe(true)
    expect((error as ApiError).userMessage).toEqual({ raw: '인증이 필요합니다.' })
  })

  it('401이 아닌 오류나 일반 에러는 isUnauthorized가 아니다', () => {
    expect(isUnauthorized(new ApiError(403, { raw: 'x' }))).toBe(false)
    expect(isUnauthorized(new Error('401'))).toBe(false)
    expect(isUnauthorized(null)).toBe(false)
  })

  it('서버 message가 없으면 상태 코드를 담은 사전 키 메시지를 쓴다', async () => {
    fetchMock.mockResolvedValue(new Response('not json', { status: 500 }))
    const error = (await fetchDailyActivities('u', '2026-01-01').catch((e: unknown) => e)) as ApiError
    expect(error.status).toBe(500)
    expect(error.userMessage).toEqual({ key: 'error.request', params: { status: 500 } })
  })

  it('캐릭터가 없으면(404) null', async () => {
    fetchMock.mockResolvedValue(jsonResponse(404, {}))
    await expect(fetchMyCharacter()).resolves.toBeNull()
  })

  it('토큰이 빠진 로그인 응답은 error.noToken', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { userId: 'google:1' }))
    const error = (await postSocialLogin('google', 'c', 'r').catch((e: unknown) => e)) as ApiError
    expect(error.userMessage).toEqual({ key: 'error.noToken' })
  })
})

const CHARACTER = { userId: 'kakao:1', character: { level: 1 } }

function v2Tokens(n: number, expiresIn = 3600) {
  return {
    userId: 'kakao:1',
    displayName: null,
    accessToken: `acc-${n}`,
    tokenType: 'Bearer' as const,
    expiresIn,
    refreshToken: `ref-${n}`,
    refreshExpiresIn: 2_592_000,
  }
}

function startV2Session(expiresIn = 3600) {
  sessionManager.start('kakao', v2Tokens(1, expiresIn))
}

function bearer(init: RequestInit | undefined): string | undefined {
  return (init?.headers as Record<string, string> | undefined)?.Authorization
}

/** /refresh는 회전된 토큰을 주고, 데이터 API는 acc-1이면 401, 그 밖의 토큰이면 200을 준다. */
function serverRotatingTo(n: number, refreshStatus = 200) {
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (url.endsWith('/api/auth/refresh')) {
      return refreshStatus === 200 ? jsonResponse(200, v2Tokens(n)) : jsonResponse(refreshStatus, { message: '만료' })
    }
    if (bearer(init) === 'Bearer acc-1') {
      return jsonResponse(401, { message: '인증이 필요합니다.' })
    }
    return url.includes('/api/characters/me')
      ? jsonResponse(200, CHARACTER)
      : jsonResponse(200, { userId: 'kakao:1', date: '2026-01-01', activities: [] })
  })
}

const refreshCalls = () => fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/api/auth/refresh'))

describe('Auth v2 토큰 갱신', () => {
  it('401이면 refresh 후 새 토큰으로 원 요청을 1회 재시도하고, 회전된 토큰을 저장한다', async () => {
    startV2Session()
    serverRotatingTo(2)

    await expect(fetchMyCharacter()).resolves.toEqual(CHARACTER)

    const urls = fetchMock.mock.calls.map(([url]) => String(url).replace(/^.*\/api/, '/api'))
    expect(urls).toEqual(['/api/characters/me', '/api/auth/refresh', '/api/characters/me'])
    expect(JSON.parse(refreshCalls()[0][1].body as string)).toEqual({ refreshToken: 'ref-1' })
    expect(bearer(fetchMock.mock.calls[2][1])).toBe('Bearer acc-2')
    // 리프레시 토큰은 localStorage, 액세스 토큰 사본은 sessionStorage에 둔다.
    const stored = JSON.parse(localStorage.getItem('wellness.session')!)
    expect(stored.refreshToken).toBe('ref-2')
    expect(stored).not.toHaveProperty('accessToken')
    expect(JSON.parse(sessionStorage.getItem('wellness.access')!).accessToken).toBe('acc-2')
    expect(sessionManager.current()?.accessToken).toBe('acc-2')
  })

  it('재시도도 401이면 더 갱신하지 않고 ApiError(401)', async () => {
    startV2Session()
    fetchMock.mockImplementation(async (url: string) =>
      url.endsWith('/api/auth/refresh') ? jsonResponse(200, v2Tokens(2)) : jsonResponse(401, {}),
    )
    const error = await fetchMyCharacter().catch((e: unknown) => e)
    expect(isUnauthorized(error)).toBe(true)
    expect(refreshCalls()).toHaveLength(1)
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('refresh가 401이면 세션을 지우고 ApiError(401)를 던진다', async () => {
    startV2Session()
    serverRotatingTo(2, 401)

    const error = await fetchMyCharacter().catch((e: unknown) => e)
    expect(isUnauthorized(error)).toBe(true)
    expect((error as ApiError).userMessage).toEqual({ key: 'error.sessionExpired' })
    expect(sessionManager.current()).toBeNull()
    expect(localStorage.getItem('wellness.session')).toBeNull()
    expect(sessionStorage.getItem('wellness.access')).toBeNull()
  })

  it('동시에 받은 401들은 refresh를 한 번만 호출하고 모두 새 토큰으로 재시도한다', async () => {
    startV2Session()
    serverRotatingTo(2)

    await Promise.all([fetchMyCharacter(), fetchDailyActivities('kakao:1', '2026-01-01'), fetchMyCharacter()])

    expect(refreshCalls()).toHaveLength(1)
    const retried = fetchMock.mock.calls.filter(([url]) => !String(url).endsWith('/refresh')).slice(3)
    expect(retried).toHaveLength(3)
    retried.forEach(([, init]) => expect(bearer(init)).toBe('Bearer acc-2'))
  })

  it('액세스 토큰 만료가 60초 이내면 요청 전에 미리 갱신한다', async () => {
    startV2Session(30)
    serverRotatingTo(2)

    await fetchMyCharacter()

    expect(fetchMock.mock.calls[0][0]).toMatch(/\/api\/auth\/refresh$/)
    expect(bearer(fetchMock.mock.calls[1][1])).toBe('Bearer acc-2')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('미리 갱신이 네트워크 오류로 실패해도 아직 유효한 토큰으로 요청한다', async () => {
    startV2Session(30)
    fetchMock.mockImplementation(async (url: string) => {
      if (url.endsWith('/api/auth/refresh')) {
        throw new TypeError('Failed to fetch')
      }
      return jsonResponse(200, CHARACTER)
    })
    await expect(fetchMyCharacter()).resolves.toEqual(CHARACTER)
    expect(bearer(fetchMock.mock.calls[1][1])).toBe('Bearer acc-1')
    expect(sessionManager.current()?.refreshToken).toBe('ref-1')
  })

  it('리프레시 토큰이 없는 기존 세션은 액세스 토큰으로 동작하고, 401이면 갱신 없이 로그아웃한다', async () => {
    startLegacySession('legacy')
    fetchMock.mockResolvedValueOnce(jsonResponse(200, CHARACTER))
    await expect(fetchMyCharacter()).resolves.toEqual(CHARACTER)
    expect(bearer(fetchMock.mock.calls[0][1])).toBe('Bearer legacy')

    fetchMock.mockResolvedValueOnce(jsonResponse(401, {}))
    const error = await fetchMyCharacter().catch((e: unknown) => e)
    expect(isUnauthorized(error)).toBe(true)
    expect(refreshCalls()).toHaveLength(0)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(sessionManager.current()).toBeNull()
    expect(localStorage.getItem('wellness.session')).toBeNull()
  })
})

describe('로그아웃', () => {
  const logoutCalls = () => fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/api/auth/logout'))

  it('Authorization 없이 refreshToken body만 보내 서버 세션을 폐기하고 로컬 세션을 지운다', async () => {
    startV2Session()
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))

    await sessionManager.logout()

    expect(logoutCalls()).toHaveLength(1)
    const init = logoutCalls()[0][1] as RequestInit
    expect(init.method).toBe('POST')
    expect(bearer(init)).toBeUndefined()
    expect(init.headers).toEqual({ 'Accept-Language': 'ko', 'Content-Type': 'application/json' })
    expect(JSON.parse(init.body as string)).toEqual({ refreshToken: 'ref-1' })
    expect(sessionManager.current()).toBeNull()
    expect(localStorage.getItem('wellness.session')).toBeNull()
  })

  it('서버 로그아웃이 실패해도 로컬 로그아웃은 진행된다', async () => {
    startV2Session()
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))
    await expect(sessionManager.logout()).resolves.toBeUndefined()
    expect(sessionManager.current()).toBeNull()
    expect(localStorage.getItem('wellness.session')).toBeNull()
  })

  it('액세스 토큰이 만료됐어도 갱신 없이 리프레시 토큰으로 바로 로그아웃 요청한다', async () => {
    sessionManager.start('kakao', v2Tokens(1, 0))
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
    await sessionManager.logout()
    expect(refreshCalls()).toHaveLength(0)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const init = logoutCalls()[0][1] as RequestInit
    expect(bearer(init)).toBeUndefined()
    expect(JSON.parse(init.body as string)).toEqual({ refreshToken: 'ref-1' })
  })

  it('리프레시 토큰이 없는 기존 세션은 서버 호출 없이 로컬만 지운다', async () => {
    await sessionManager.logout()
    expect(fetchMock).not.toHaveBeenCalled()
    expect(sessionManager.current()).toBeNull()
  })
})
