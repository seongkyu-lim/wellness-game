import { getLanguage, t } from '../i18n/core'
import type { CharacterSummary, DailyActivitiesResponse } from './types'

const BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/** 토큰이 없거나 만료·무효(401). 호출 측은 세션을 지우고 로그인 안내로 돌아가야 한다. */
export function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401
}

/** 서버가 응답 메시지를 번역하도록 현재 언어(ko|en)를 항상 함께 보낸다. */
function languageHeaders(): Record<string, string> {
  return { 'Accept-Language': getLanguage() }
}

function authHeaders(accessToken: string): HeadersInit {
  return { ...languageHeaders(), Authorization: `Bearer ${accessToken}` }
}

/** 로그인한 본인의 캐릭터 조회. 아직 캐릭터가 없으면 null. */
export async function fetchMyCharacter(accessToken: string): Promise<CharacterSummary | null> {
  const res = await fetch(`${BASE_URL}/api/characters/me`, { headers: authHeaders(accessToken) })
  if (res.status === 404) {
    return null
  }
  if (!res.ok) {
    throw new ApiError(res.status, await errorMessage(res))
  }
  return res.json()
}

/**
 * iOS 앱이 동기화해둔 본인의 하루치 활동 기록을 조회한다.
 * userId는 서버가 토큰 주체와 대조하므로 반드시 세션의 userId를 넘긴다.
 */
export async function fetchDailyActivities(
  accessToken: string,
  userId: string,
  date: string,
): Promise<DailyActivitiesResponse> {
  const params = new URLSearchParams({ userId, date })
  const res = await fetch(`${BASE_URL}/api/health-activities?${params}`, { headers: authHeaders(accessToken) })
  if (!res.ok) {
    throw new ApiError(res.status, await errorMessage(res))
  }
  return res.json()
}

/** 백엔드가 발급하는 토큰 응답 공통 필드. */
export interface TokenResponse {
  userId: string
  displayName: string | null
  accessToken: string
  tokenType: 'Bearer'
  /** 초 단위 유효 기간 */
  expiresIn: number
}

/** authorization code를 백엔드에서 교환하고 서버 발급 accessToken을 받는다. */
export async function postSocialLogin(provider: string, code: string, redirectUri: string): Promise<TokenResponse> {
  const res = await fetch(`${BASE_URL}/api/auth/${encodeURIComponent(provider)}`, {
    method: 'POST',
    headers: { ...languageHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ code, redirectUri }),
  })
  if (!res.ok) {
    throw new ApiError(res.status, await errorMessage(res))
  }
  const body = (await res.json()) as Partial<TokenResponse>
  if (typeof body.accessToken !== 'string' || !body.accessToken || typeof body.userId !== 'string') {
    throw new ApiError(res.status, t('error.noToken'))
  }
  return body as TokenResponse
}

async function errorMessage(res: Response): Promise<string> {
  try {
    const body = (await res.json()) as { message?: string }
    return body.message ?? t('error.request', { status: res.status })
  } catch {
    return t('error.request', { status: res.status })
  }
}
