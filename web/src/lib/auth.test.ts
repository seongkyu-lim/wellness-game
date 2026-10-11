import { beforeEach, describe, expect, it, vi } from 'vitest'
import { LocalizedError } from '../i18n/core'
import { clearSession, completeLoginFromRedirect, loadSession, type Session } from './auth'

const SESSION_KEY = 'wellness.session'
const STATE_KEY = 'wellness.oauth-state'

function validSession(overrides: Partial<Session> = {}): Session {
  return {
    provider: 'kakao',
    userId: 'kakao:1',
    displayName: '홍길동',
    accessToken: 'tok',
    expiresAt: Date.now() + 60_000,
    ...overrides,
  }
}

describe('loadSession', () => {
  it('유효한 세션을 돌려준다', () => {
    const session = validSession()
    localStorage.setItem(SESSION_KEY, JSON.stringify(session))
    expect(loadSession()).toEqual(session)
  })

  it('저장된 세션이 없으면 null', () => {
    expect(loadSession()).toBeNull()
  })

  it.each([
    ['만료된 세션', JSON.stringify(validSession({ expiresAt: Date.now() - 1 }))],
    ['토큰이 없는 예전 형식', JSON.stringify({ provider: 'kakao', userId: 'kakao:1', displayName: null })],
    ['알 수 없는 provider', JSON.stringify(validSession({ provider: 'apple' as Session['provider'] }))],
    ['손상된 JSON', '{not json'],
  ])('%s는 지우고 null', (_, raw) => {
    localStorage.setItem(SESSION_KEY, raw)
    expect(loadSession()).toBeNull()
    expect(localStorage.getItem(SESSION_KEY)).toBeNull()
  })

  it('예전 게스트 키를 정리한다', () => {
    localStorage.setItem('wellness.userId', 'guest')
    loadSession()
    expect(localStorage.getItem('wellness.userId')).toBeNull()
  })

  it('clearSession은 저장된 세션을 지운다', () => {
    localStorage.setItem(SESSION_KEY, JSON.stringify(validSession()))
    clearSession()
    expect(loadSession()).toBeNull()
  })
})

describe('completeLoginFromRedirect', () => {
  beforeEach(() => window.history.replaceState(null, '', '/'))

  it('code가 없으면 null', async () => {
    await expect(completeLoginFromRedirect()).resolves.toBeNull()
  })

  it('state가 다르면 error.stateMismatch 키를 가진 LocalizedError', async () => {
    sessionStorage.setItem(STATE_KEY, 'kakao:expected')
    window.history.replaceState(null, '', '/?code=c&state=kakao:forged')
    const error = await completeLoginFromRedirect().catch((e: unknown) => e)
    expect(error).toBeInstanceOf(LocalizedError)
    expect((error as LocalizedError).userMessage).toEqual({ key: 'error.stateMismatch' })
    expect(sessionStorage.getItem(STATE_KEY)).toBeNull()
    expect(window.location.search).toBe('')
  })

  it('지원하지 않는 provider면 error.unsupportedProvider', async () => {
    sessionStorage.setItem(STATE_KEY, 'apple:x')
    window.history.replaceState(null, '', '/?code=c&state=apple:x')
    const error = (await completeLoginFromRedirect().catch((e: unknown) => e)) as LocalizedError
    expect(error.userMessage).toEqual({ key: 'error.unsupportedProvider' })
  })

  it('code를 토큰으로 교환해 세션을 저장한다', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(1_000_000)
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({ userId: 'naver:9', displayName: null, accessToken: 'srv', tokenType: 'Bearer', expiresIn: 3600 }),
        { status: 200 },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    sessionStorage.setItem(STATE_KEY, 'naver:abc')
    window.history.replaceState(null, '', '/?code=the-code&state=naver:abc')

    const session = await completeLoginFromRedirect()

    const expected: Session = {
      provider: 'naver',
      userId: 'naver:9',
      displayName: null,
      accessToken: 'srv',
      expiresAt: 1_000_000 + 3_600_000,
    }
    expect(session).toEqual(expected)
    expect(JSON.parse(localStorage.getItem(SESSION_KEY)!)).toEqual(expected)
    expect(fetchMock.mock.calls[0][0]).toMatch(/\/api\/auth\/naver$/)
  })
})
