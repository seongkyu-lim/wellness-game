import { sessionManager } from '../lib/session'
import { ApiError, BASE_URL, errorMessage, languageHeaders } from './http'
import type { CharacterSummary, DailyActivitiesResponse } from './types'

export { ApiError, isUnauthorized } from './http'
export { postSocialLogin, type TokenResponse } from './authApi'

function authHeaders(accessToken: string): HeadersInit {
  return { ...languageHeaders(), Authorization: `Bearer ${accessToken}` }
}

/**
 * 현재 세션의 액세스 토큰으로 GET한다. 만료 60초 이내면 먼저 갱신하고, 401이면 토큰을 한 번 갱신해 1회만 재시도한다.
 * 갱신할 수 없으면 세션을 지우고 401 응답(또는 ApiError(401))으로 끝난다.
 */
async function authorizedFetch(url: string): Promise<Response> {
  const token = await sessionManager.accessToken()
  const res = await fetch(url, { headers: authHeaders(token) })
  if (res.status !== 401) {
    return res
  }
  const fresh = await sessionManager.handleUnauthorized(token)
  return fresh ? fetch(url, { headers: authHeaders(fresh) }) : res
}

/** 로그인한 본인의 캐릭터 조회. 아직 캐릭터가 없으면 null. */
export async function fetchMyCharacter(): Promise<CharacterSummary | null> {
  const res = await authorizedFetch(`${BASE_URL}/api/characters/me`)
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
export async function fetchDailyActivities(userId: string, date: string): Promise<DailyActivitiesResponse> {
  const params = new URLSearchParams({ userId, date })
  const res = await authorizedFetch(`${BASE_URL}/api/health-activities?${params}`)
  if (!res.ok) {
    throw new ApiError(res.status, await errorMessage(res))
  }
  return res.json()
}
