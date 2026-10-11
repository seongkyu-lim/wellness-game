import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setLanguage } from '../i18n/core'
import { ApiError, fetchDailyActivities, fetchMyCharacter, isUnauthorized, postSocialLogin } from './client'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

let fetchMock: ReturnType<typeof vi.fn>

beforeEach(() => {
  fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
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
    await fetchMyCharacter('tok-123')
    expect(fetchMock.mock.calls[0][0]).toMatch(/\/api\/characters\/me$/)
    expect(lastHeaders()).toEqual({ 'Accept-Language': 'en', Authorization: 'Bearer tok-123' })
  })

  it('Accept-Language는 요청 시점의 현재 언어를 따른다', async () => {
    fetchMock.mockImplementation(async () => jsonResponse(200, { userId: 'u', date: '2026-01-01', activities: [] }))
    setLanguage('ko')
    await fetchDailyActivities('t', 'kakao:1', '2026-01-01')
    expect(lastHeaders()['Accept-Language']).toBe('ko')
    setLanguage('en')
    await fetchDailyActivities('t', 'kakao:1', '2026-01-01')
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
})

describe('오류 처리', () => {
  it('401은 isUnauthorized로 판별된다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(401, { message: '인증이 필요합니다.' }))
    const error = await fetchMyCharacter('expired').catch((e: unknown) => e)
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
    const error = (await fetchDailyActivities('t', 'u', '2026-01-01').catch((e: unknown) => e)) as ApiError
    expect(error.status).toBe(500)
    expect(error.userMessage).toEqual({ key: 'error.request', params: { status: 500 } })
  })

  it('캐릭터가 없으면(404) null', async () => {
    fetchMock.mockResolvedValue(jsonResponse(404, {}))
    await expect(fetchMyCharacter('t')).resolves.toBeNull()
  })

  it('토큰이 빠진 로그인 응답은 error.noToken', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { userId: 'google:1' }))
    const error = (await postSocialLogin('google', 'c', 'r').catch((e: unknown) => e)) as ApiError
    expect(error.userMessage).toEqual({ key: 'error.noToken' })
  })
})
