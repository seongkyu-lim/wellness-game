import { ApiError, BASE_URL, errorMessage, languageHeaders } from './http'

/** 백엔드가 발급하는 토큰 응답 공통 필드. */
export interface TokenResponse {
  userId: string
  displayName?: string | null
  accessToken: string
  tokenType: 'Bearer'
  /** 액세스 토큰 유효 기간(초) */
  expiresIn: number
  /** Auth v2 서버만 준다. 없으면 리프레시 없이 액세스 토큰 만료까지만 쓰는 세션이 된다. */
  refreshToken?: string
  /** 리프레시 토큰 유효 기간(초) */
  refreshExpiresIn?: number
}

const JSON_HEADERS = { 'Content-Type': 'application/json' }

async function readTokenResponse(res: Response): Promise<TokenResponse> {
  if (!res.ok) {
    throw new ApiError(res.status, await errorMessage(res))
  }
  const body = (await res.json()) as Partial<TokenResponse>
  if (typeof body.accessToken !== 'string' || !body.accessToken || typeof body.userId !== 'string') {
    throw new ApiError(res.status, { key: 'error.noToken' })
  }
  return body as TokenResponse
}

/** authorization code를 백엔드에서 교환하고 서버 발급 토큰을 받는다. */
export async function postSocialLogin(provider: string, code: string, redirectUri: string): Promise<TokenResponse> {
  const res = await fetch(`${BASE_URL}/api/auth/${encodeURIComponent(provider)}`, {
    method: 'POST',
    headers: { ...languageHeaders(), ...JSON_HEADERS },
    body: JSON.stringify({ code, redirectUri }),
  })
  return readTokenResponse(res)
}

/**
 * 리프레시 토큰을 회전한다. 성공하면 새 액세스·리프레시 토큰을 받고, 보낸 토큰은 서버에서 폐기된다.
 * 없음·만료·재사용이면 401. 같은 토큰으로 동시에 부르면 재사용으로 판정되므로 호출은 직렬화해야 한다.
 */
export async function postRefresh(refreshToken: string): Promise<TokenResponse> {
  const res = await fetch(`${BASE_URL}/api/auth/refresh`, {
    method: 'POST',
    headers: { ...languageHeaders(), ...JSON_HEADERS },
    body: JSON.stringify({ refreshToken }),
  })
  const body = await readTokenResponse(res)
  if (typeof body.refreshToken !== 'string' || !body.refreshToken) {
    throw new ApiError(res.status, { key: 'error.noToken' })
  }
  return body
}

/** 이 기기 세션(리프레시 토큰 family)을 서버에서 폐기한다. 성공은 204. */
export async function postLogout(accessToken: string, refreshToken: string): Promise<void> {
  const res = await fetch(`${BASE_URL}/api/auth/logout`, {
    method: 'POST',
    headers: { ...languageHeaders(), ...JSON_HEADERS, Authorization: `Bearer ${accessToken}` },
    body: JSON.stringify({ refreshToken }),
  })
  if (!res.ok) {
    throw new ApiError(res.status, await errorMessage(res))
  }
}
