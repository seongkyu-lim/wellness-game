import type { CharacterSummary, SyncRequest, SyncResponse } from './types'

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

/** 활동 기록을 동기화하고 획득 XP와 갱신된 캐릭터를 받는다. */
export async function syncActivities(request: SyncRequest): Promise<SyncResponse> {
  const res = await fetch(`${BASE_URL}/api/health-activities/sync`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
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
