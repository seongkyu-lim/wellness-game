import { postSocialLogin } from '../api/authApi'
import { LocalizedError } from '../i18n/core'
import { codeChallengeS256, createCodeVerifier } from './pkce'
import { isProvider, sessionManager, type Provider, type Session } from './session'

export type { Provider, Session } from './session'

const STATE_KEY = 'wellness.oauth-state'
const VERIFIER_KEY = 'wellness.oauth-verifier'

/** PKCE(S256)를 지원하는 provider. naver는 지원하지 않아 state만 쓴다. */
const PKCE_PROVIDERS: ReadonlySet<Provider> = new Set(['google', 'kakao'])

const CLIENT_IDS: Record<Provider, string | undefined> = {
  google: import.meta.env.VITE_GOOGLE_CLIENT_ID,
  kakao: import.meta.env.VITE_KAKAO_CLIENT_ID,
  naver: import.meta.env.VITE_NAVER_CLIENT_ID,
}

export function isConfigured(provider: Provider): boolean {
  return Boolean(CLIENT_IDS[provider])
}

/**
 * 저장된 세션을 읽는다. 토큰이 없는 예전 형식이거나 만료됐거나 손상된 세션은
 * 지우고 로그아웃 상태(null)로 처리한다.
 */
export function loadSession(): Session | null {
  return sessionManager.load()
}

/** 로컬 세션만 지운다(401 등). 다른 탭에는 로그인 만료로 알린다. */
export function clearSession(): void {
  sessionManager.clear('expired')
}

/** 사용자 로그아웃. 로컬 세션을 지우고 서버에 리프레시 토큰 폐기를 요청한다(실패해도 로컬 로그아웃은 유지). */
export function logout(): Promise<void> {
  return sessionManager.logout()
}

/** 다른 탭에서 로그아웃·세션 만료가 일어나면 호출된다. 해제 함수를 돌려준다. */
export const onRemoteSignOut = sessionManager.onRemoteSignOut.bind(sessionManager)

/** provider 인증 페이지로 이동한다. 완료되면 같은 주소로 code와 함께 돌아온다. */
export async function startLogin(provider: Provider): Promise<void> {
  window.location.href = await buildLoginUrl(provider)
}

/**
 * state(와 PKCE 지원 provider면 code_verifier)를 sessionStorage에 두고 인가 URL을 만든다.
 * code_verifier는 브라우저에만 남기고 인가 요청에는 code_challenge(S256)만 보낸다.
 */
export async function buildLoginUrl(provider: Provider): Promise<string> {
  const state = `${provider}:${crypto.randomUUID()}`
  let codeChallenge: string | undefined
  if (PKCE_PROVIDERS.has(provider)) {
    const verifier = createCodeVerifier()
    sessionStorage.setItem(VERIFIER_KEY, verifier)
    codeChallenge = await codeChallengeS256(verifier)
  } else {
    sessionStorage.removeItem(VERIFIER_KEY)
  }
  sessionStorage.setItem(STATE_KEY, state)
  return authorizeUrl(provider, state, codeChallenge)
}

/**
 * 리다이렉트로 돌아온 code를 백엔드에서 토큰·프로필로 교환해 세션을 만든다.
 * 로그인 리다이렉트가 아니면 null.
 */
export async function completeLoginFromRedirect(): Promise<Session | null> {
  const params = new URLSearchParams(window.location.search)
  const code = params.get('code')
  const state = params.get('state')
  if (!code || !state) {
    return null
  }

  const savedState = sessionStorage.getItem(STATE_KEY)
  const codeVerifier = sessionStorage.getItem(VERIFIER_KEY) ?? undefined
  // state·verifier는 한 번만 쓴다. 검증 실패해도 남기지 않는다.
  sessionStorage.removeItem(STATE_KEY)
  sessionStorage.removeItem(VERIFIER_KEY)
  window.history.replaceState(null, '', window.location.pathname)

  if (state !== savedState) {
    throw new LocalizedError({ key: 'error.stateMismatch' })
  }

  const provider = state.split(':')[0]
  if (!isProvider(provider)) {
    throw new LocalizedError({ key: 'error.unsupportedProvider' })
  }
  const verifier = PKCE_PROVIDERS.has(provider) ? codeVerifier : undefined
  const result = await postSocialLogin(provider, code, redirectUri(), verifier)
  return sessionManager.start(provider, result)
}

function redirectUri(): string {
  // provider 콘솔에 등록하는 Redirect URI와 정확히 일치해야 한다.
  // 하위 경로에 배포하면(vite --base) BASE_URL이 '/<repo>/'가 된다. (로컬은 '/')
  return `${window.location.origin}${import.meta.env.BASE_URL}`
}

/** codeChallenge가 있으면 PKCE(S256) 파라미터를 붙인다. */
function authorizeUrl(provider: Provider, state: string, codeChallenge?: string): string {
  const params = new URLSearchParams({
    client_id: CLIENT_IDS[provider]!,
    redirect_uri: redirectUri(),
    response_type: 'code',
    state,
  })
  if (codeChallenge) {
    params.set('code_challenge', codeChallenge)
    params.set('code_challenge_method', 'S256')
  }
  switch (provider) {
    case 'kakao':
      return `https://kauth.kakao.com/oauth/authorize?${params}`
    case 'naver':
      return `https://nid.naver.com/oauth2.0/authorize?${params}`
    case 'google':
      params.set('scope', 'openid profile')
      return `https://accounts.google.com/o/oauth2/v2/auth?${params}`
  }
}
