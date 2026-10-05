import { postSocialLogin } from '../api/client'
import { t } from '../i18n/core'

// iOS 앱과 동일한 provider 집합 (Apple은 유료 개발자 계정 + HTTPS 필요로 웹 미지원)
export type Provider = 'google' | 'kakao' | 'naver'

export interface Session {
  provider: Provider
  userId: string
  displayName: string | null
  /** 백엔드가 발급한 Bearer 토큰. 보호 API 호출에 사용한다. */
  accessToken: string
  /** 토큰 만료 시각 (epoch ms) */
  expiresAt: number
}

const SESSION_KEY = 'wellness.session'
const STATE_KEY = 'wellness.oauth-state'
// 제거된 게스트 기능이 남긴 키. 더 이상 사용하지 않으므로 발견하면 지운다.
const LEGACY_GUEST_ID_KEY = 'wellness.userId'
const PROVIDERS: readonly Provider[] = ['google', 'kakao', 'naver']

function isProvider(value: unknown): value is Provider {
  return PROVIDERS.includes(value as Provider)
}

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
  localStorage.removeItem(LEGACY_GUEST_ID_KEY)
  const raw = localStorage.getItem(SESSION_KEY)
  if (!raw) {
    return null
  }
  try {
    const parsed: unknown = JSON.parse(raw)
    if (isValidSession(parsed) && parsed.expiresAt > Date.now()) {
      return parsed
    }
  } catch {
    // 손상된 값은 아래에서 제거한다.
  }
  localStorage.removeItem(SESSION_KEY)
  return null
}

function isValidSession(value: unknown): value is Session {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const v = value as Record<string, unknown>
  return (
    isProvider(v.provider) &&
    typeof v.userId === 'string' &&
    v.userId.length > 0 &&
    typeof v.accessToken === 'string' &&
    v.accessToken.length > 0 &&
    typeof v.expiresAt === 'number' &&
    (v.displayName === null || typeof v.displayName === 'string')
  )
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
    throw new Error(t('error.stateMismatch'))
  }

  const provider = state.split(':')[0]
  if (!isProvider(provider)) {
    throw new Error(t('error.unsupportedProvider'))
  }
  const result = await postSocialLogin(provider, code, redirectUri())
  const session: Session = {
    provider,
    userId: result.userId,
    displayName: result.displayName ?? null,
    accessToken: result.accessToken,
    expiresAt: Date.now() + Math.max(0, result.expiresIn) * 1000,
  }
  localStorage.setItem(SESSION_KEY, JSON.stringify(session))
  return session
}

function redirectUri(): string {
  // provider 콘솔에 등록하는 Redirect URI와 정확히 일치해야 한다.
  // GitHub Pages처럼 하위 경로에 배포하면 BASE_URL이 '/<repo>/'가 된다. (로컬은 '/')
  return `${window.location.origin}${import.meta.env.BASE_URL}`
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
