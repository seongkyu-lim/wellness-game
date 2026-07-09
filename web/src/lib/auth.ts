import { postSocialLogin } from '../api/client'

// iOS 앱과 동일한 provider 집합 (Apple은 유료 개발자 계정 + HTTPS 필요로 웹 미지원)
export type Provider = 'google' | 'kakao' | 'naver'

export interface Session {
  provider: Provider
  userId: string
  displayName: string | null
}

const SESSION_KEY = 'wellness.session'
const STATE_KEY = 'wellness.oauth-state'

const CLIENT_IDS: Record<Provider, string | undefined> = {
  google: import.meta.env.VITE_GOOGLE_CLIENT_ID,
  kakao: import.meta.env.VITE_KAKAO_CLIENT_ID,
  naver: import.meta.env.VITE_NAVER_CLIENT_ID,
}

export function isConfigured(provider: Provider): boolean {
  return Boolean(CLIENT_IDS[provider])
}

export function loadSession(): Session | null {
  const raw = localStorage.getItem(SESSION_KEY)
  if (!raw) {
    return null
  }
  try {
    return JSON.parse(raw) as Session
  } catch {
    localStorage.removeItem(SESSION_KEY)
    return null
  }
}

export function clearSession(): void {
  localStorage.removeItem(SESSION_KEY)
}

/** provider 인증 페이지로 이동한다. 완료되면 같은 주소로 code와 함께 돌아온다. */
export function startLogin(provider: Provider): void {
  const state = `${provider}:${crypto.randomUUID()}`
  sessionStorage.setItem(STATE_KEY, state)
  window.location.href = authorizeUrl(provider, state)
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
  sessionStorage.removeItem(STATE_KEY)
  window.history.replaceState(null, '', window.location.pathname)

  if (state !== savedState) {
    throw new Error('로그인 상태 검증에 실패했습니다. 다시 시도해 주세요.')
  }

  const provider = state.split(':')[0] as Provider
  const result = await postSocialLogin(provider, code, redirectUri())
  const session: Session = {
    provider,
    userId: result.userId,
    displayName: result.displayName,
  }
  localStorage.setItem(SESSION_KEY, JSON.stringify(session))
  return session
}

function redirectUri(): string {
  // provider 콘솔에 등록하는 Redirect URI와 정확히 일치해야 한다.
  return `${window.location.origin}/`
}

function authorizeUrl(provider: Provider, state: string): string {
  const params = new URLSearchParams({
    client_id: CLIENT_IDS[provider]!,
    redirect_uri: redirectUri(),
    response_type: 'code',
    state,
  })
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
