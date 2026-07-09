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

/** 캐릭터 조회. 아직 캐릭터가 없는 사용자는 null. */
export async function fetchCharacter(userId: string): Promise<CharacterSummary | null> {
  const res = await fetch(`${BASE_URL}/api/characters/${encodeURIComponent(userId)}`)
  if (res.status === 404) {
    return null
  }
  if (!res.ok) {
    throw new ApiError(res.status, await errorMessage(res))
  }
  return res.json()
}

/** iOS 앱이 동기화해둔 하루치 활동 기록을 조회한다. */
export async function fetchDailyActivities(userId: string, date: string): Promise<DailyActivitiesResponse> {
  const params = new URLSearchParams({ userId, date })
  const res = await fetch(`${BASE_URL}/api/health-activities?${params}`)
  if (!res.ok) {
    throw new ApiError(res.status, await errorMessage(res))
  }
  return res.json()
}

export interface SocialLoginResponse {
  userId: string
  displayName: string | null
}

/** authorization code를 백엔드에서 provider 토큰·프로필로 교환한다. */
export async function postSocialLogin(provider: string, code: string, redirectUri: string): Promise<SocialLoginResponse> {
  const res = await fetch(`${BASE_URL}/api/auth/${encodeURIComponent(provider)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code, redirectUri }),
  })
  if (!res.ok) {
    throw new ApiError(res.status, await errorMessage(res))
  }
  return res.json()
}

async function errorMessage(res: Response): Promise<string> {
  try {
    const body = (await res.json()) as { message?: string }
    return body.message ?? `요청에 실패했습니다. (${res.status})`
  } catch {
    return `요청에 실패했습니다. (${res.status})`
  }
}
