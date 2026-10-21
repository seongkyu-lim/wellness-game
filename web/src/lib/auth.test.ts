import { beforeEach, describe, expect, it, vi } from 'vitest'
import { LocalizedError } from '../i18n/core'
import { buildLoginUrl, clearSession, completeLoginFromRedirect, loadSession, type Session } from './auth'
import { codeChallengeS256 } from './pkce'

const SESSION_KEY = 'wellness.session'
const STATE_KEY = 'wellness.oauth-state'
const VERIFIER_KEY = 'wellness.oauth-verifier'
const RFC_VERIFIER = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk'

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

  it('Auth v2 응답이면 리프레시 토큰과 만료 시각을 저장하고 액세스 토큰은 localStorage에 두지 않는다', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(1_000_000)
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            userId: 'kakao:1',
            displayName: '홍길동',
            accessToken: 'acc',
            tokenType: 'Bearer',
            expiresIn: 3600,
            refreshToken: 'ref',
            refreshExpiresIn: 2_592_000,
          }),
          { status: 200 },
        ),
      ),
    )
    sessionStorage.setItem(STATE_KEY, 'kakao:s')
    window.history.replaceState(null, '', '/?code=c&state=kakao:s')

    const session = await completeLoginFromRedirect()

    expect(session).toEqual({
      provider: 'kakao',
      userId: 'kakao:1',
      displayName: '홍길동',
      accessToken: 'acc',
      expiresAt: 1_000_000 + 3_600_000,
      refreshToken: 'ref',
      refreshExpiresAt: 1_000_000 + 2_592_000_000,
    })
    expect(JSON.parse(localStorage.getItem(SESSION_KEY)!)).toEqual({
      provider: 'kakao',
      userId: 'kakao:1',
      displayName: '홍길동',
      refreshToken: 'ref',
      refreshExpiresAt: 1_000_000 + 2_592_000_000,
    })
    expect(JSON.parse(sessionStorage.getItem('wellness.access')!)).toEqual({
      userId: 'kakao:1',
      accessToken: 'acc',
      expiresAt: 1_000_000 + 3_600_000,
    })
  })
})

describe('PKCE', () => {
  beforeEach(() => window.history.replaceState(null, '', '/'))

  function tokenFetch() {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ userId: 'u', accessToken: 'a', tokenType: 'Bearer', expiresIn: 60 }), { status: 200 }),
    )
    vi.stubGlobal('fetch', fetchMock)
    return fetchMock
  }

  it.each(['google', 'kakao'] as const)(
    '%s는 code_verifier를 sessionStorage에 두고 그 S256 값을 code_challenge로 보낸다',
    async (provider) => {
      const url = new URL(await buildLoginUrl(provider))
      const verifier = sessionStorage.getItem(VERIFIER_KEY)!
      expect(verifier).toMatch(/^[A-Za-z0-9\-._~]{43,128}$/)
      expect(url.searchParams.get('code_challenge')).toBe(await codeChallengeS256(verifier))
      expect(url.searchParams.get('code_challenge_method')).toBe('S256')
      expect(url.searchParams.get('state')).toBe(sessionStorage.getItem(STATE_KEY))
      expect(url.searchParams.get('state')).toMatch(new RegExp(`^${provider}:`))
      expect(url.searchParams.get('response_type')).toBe('code')
      // verifier 자체는 인가 요청에 실리지 않는다.
      expect(url.toString()).not.toContain(verifier)
    },
  )

  it('로그인마다 다른 code_verifier를 만든다', async () => {
    await buildLoginUrl('google')
    const first = sessionStorage.getItem(VERIFIER_KEY)
    await buildLoginUrl('google')
    expect(sessionStorage.getItem(VERIFIER_KEY)).not.toBe(first)
  })

  it('naver는 PKCE 파라미터 없이 보내고 남아 있던 code_verifier도 지운다', async () => {
    sessionStorage.setItem(VERIFIER_KEY, RFC_VERIFIER)
    const url = new URL(await buildLoginUrl('naver'))
    expect(url.searchParams.has('code_challenge')).toBe(false)
    expect(url.searchParams.has('code_challenge_method')).toBe(false)
    expect(url.searchParams.get('state')).toMatch(/^naver:/)
    expect(sessionStorage.getItem(VERIFIER_KEY)).toBeNull()
  })

  it('콜백에서 code·redirectUri와 함께 codeVerifier를 보내고 사용 후 지운다', async () => {
    const fetchMock = tokenFetch()
    sessionStorage.setItem(STATE_KEY, 'google:s')
    sessionStorage.setItem(VERIFIER_KEY, RFC_VERIFIER)
    window.history.replaceState(null, '', '/?code=the-code&state=google:s')

    await completeLoginFromRedirect()

    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(JSON.parse(init.body as string)).toEqual({
      code: 'the-code',
      redirectUri: `${window.location.origin}/`,
      codeVerifier: RFC_VERIFIER,
    })
    expect(sessionStorage.getItem(VERIFIER_KEY)).toBeNull()
  })

  it('naver 콜백에는 codeVerifier를 보내지 않는다', async () => {
    const fetchMock = tokenFetch()
    sessionStorage.setItem(STATE_KEY, 'naver:s')
    sessionStorage.setItem(VERIFIER_KEY, RFC_VERIFIER)
    window.history.replaceState(null, '', '/?code=c&state=naver:s')

    await completeLoginFromRedirect()

    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(JSON.parse(init.body as string)).not.toHaveProperty('codeVerifier')
    expect(sessionStorage.getItem(VERIFIER_KEY)).toBeNull()
  })

  it('state가 다르면 교환하지 않고 code_verifier도 지운다', async () => {
    const fetchMock = tokenFetch()
    sessionStorage.setItem(STATE_KEY, 'kakao:expected')
    sessionStorage.setItem(VERIFIER_KEY, RFC_VERIFIER)
    window.history.replaceState(null, '', '/?code=c&state=kakao:forged')

    await expect(completeLoginFromRedirect()).rejects.toBeInstanceOf(LocalizedError)
    expect(fetchMock).not.toHaveBeenCalled()
    expect(sessionStorage.getItem(VERIFIER_KEY)).toBeNull()
  })
})

describe('인가 URL', () => {
  it.each([
    ['kakao', 'https://kauth.kakao.com/oauth/authorize'],
    ['naver', 'https://nid.naver.com/oauth2.0/authorize'],
    ['google', 'https://accounts.google.com/o/oauth2/v2/auth'],
  ] as const)('%s는 provider 인가 주소로 보내고 redirect_uri는 앱 기준 주소다', async (provider, endpoint) => {
    const url = new URL(await buildLoginUrl(provider))
    expect(`${url.origin}${url.pathname}`).toBe(endpoint)
    expect(url.searchParams.get('redirect_uri')).toBe(`${window.location.origin}/`)
    expect(url.searchParams.get('scope')).toBe(provider === 'google' ? 'openid profile' : null)
  })

  it('로그인 시작이 겹쳐도 마지막 state·verifier 한 쌍만 남는다', async () => {
    const [first, second] = await Promise.all([buildLoginUrl('kakao'), buildLoginUrl('google')])
    const saved = sessionStorage.getItem(STATE_KEY)
    const winner = [first, second].map((u) => new URL(u)).find((u) => u.searchParams.get('state') === saved)!
    expect(winner).toBeDefined()
    expect(winner.searchParams.get('code_challenge')).toBe(await codeChallengeS256(sessionStorage.getItem(VERIFIER_KEY)!))
  })
})

describe('콜백 오류 처리', () => {
  beforeEach(() => window.history.replaceState(null, '', '/'))

  it('code만 있고 state가 없으면 로그인 리다이렉트로 보지 않고 저장값·주소를 건드리지 않는다', async () => {
    sessionStorage.setItem(STATE_KEY, 'kakao:s')
    window.history.replaceState(null, '', '/?code=c')
    await expect(completeLoginFromRedirect()).resolves.toBeNull()
    expect(sessionStorage.getItem(STATE_KEY)).toBe('kakao:s')
    expect(window.location.search).toBe('?code=c')
  })

  it('저장된 state가 없으면(다른 탭·만료된 탭에서 돌아옴) 교환하지 않고 state 불일치로 끝낸다', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    window.history.replaceState(null, '', '/?code=c&state=kakao:s')
    const error = (await completeLoginFromRedirect().catch((e: unknown) => e)) as LocalizedError
    expect(error.userMessage).toEqual({ key: 'error.stateMismatch' })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('교환이 실패해도 state를 소비하고 주소를 정리해, 새로고침으로 같은 code를 다시 보내지 않는다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ message: '만료된 코드' }), { status: 400 }))
    vi.stubGlobal('fetch', fetchMock)
    sessionStorage.setItem(STATE_KEY, 'kakao:s')
    window.history.replaceState(null, '', '/?code=c&state=kakao:s')

    const error = (await completeLoginFromRedirect().catch((e: unknown) => e)) as LocalizedError
    expect(error.userMessage).toEqual({ raw: '만료된 코드' })
    expect(window.location.search).toBe('')
    expect(sessionStorage.getItem(STATE_KEY)).toBeNull()
    expect(localStorage.getItem(SESSION_KEY)).toBeNull()

    await expect(completeLoginFromRedirect()).resolves.toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
