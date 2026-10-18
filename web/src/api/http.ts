import { DEFAULT_API_BASE_URL } from '../../csp'
import { getLanguage, LocalizedError, type Message } from '../i18n/core'

export const BASE_URL: string = import.meta.env.VITE_API_BASE_URL || DEFAULT_API_BASE_URL

/** HTTP 실패. 화면 문구는 서버 메시지(raw) 또는 사전 키로 보관해 렌더링 시 번역한다. */
export class ApiError extends LocalizedError {
  constructor(
    readonly status: number,
    userMessage: Message,
  ) {
    super(userMessage)
    this.name = 'ApiError'
  }
}

/** 토큰이 없거나 만료·무효(401). 호출 측은 세션을 지우고 로그인 안내로 돌아가야 한다. */
export function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401
}

/** 서버 요청 없이 로컬에서 세션이 끝났다고 판단할 때 쓰는 401. */
export function sessionExpiredError(): ApiError {
  return new ApiError(401, { key: 'error.sessionExpired' })
}

/** 서버가 응답 메시지를 번역하도록 현재 언어(ko|en)를 항상 함께 보낸다. */
export function languageHeaders(): Record<string, string> {
  return { 'Accept-Language': getLanguage() }
}

/** 실패 응답이면 서버 메시지를 담은 ApiError를 던진다. */
export async function ensureOk(res: Response): Promise<void> {
  if (!res.ok) {
    throw new ApiError(res.status, await errorMessage(res))
  }
}

/** 서버가 번역해 보낸 message가 있으면 그대로(raw), 없으면 상태 코드를 담은 사전 키로 돌려준다. */
export async function errorMessage(res: Response): Promise<Message> {
  const fallback: Message = { key: 'error.request', params: { status: res.status } }
  try {
    const body = (await res.json()) as { message?: unknown }
    return typeof body.message === 'string' && body.message ? { raw: body.message } : fallback
  } catch {
    return fallback
  }
}
