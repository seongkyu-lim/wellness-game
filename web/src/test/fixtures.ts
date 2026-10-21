import type { TokenResponse } from '../api/authApi'
import type { ActivityEntry, Character } from '../api/types'

/** 테스트 공용 데이터 팩토리. */

export const USER_ID = 'kakao:1'

/** n번째 회전의 Auth v2 토큰 응답(acc-n·ref-n). */
export function tokenResponse(n: number, expiresIn = 3600, overrides: Partial<TokenResponse> = {}): TokenResponse {
  return {
    userId: USER_ID,
    displayName: null,
    accessToken: `acc-${n}`,
    tokenType: 'Bearer',
    expiresIn,
    refreshToken: `ref-${n}`,
    refreshExpiresIn: 2_592_000,
    ...overrides,
  }
}

/** 값이 모두 비어 있는 활동 기록에 필요한 필드만 덮어쓴다. */
export function activityEntry(overrides: Partial<ActivityEntry> = {}): ActivityEntry {
  return {
    type: 'STEPS',
    source: 'HEALTHKIT',
    durationMinutes: null,
    calories: null,
    distanceMeters: null,
    steps: null,
    sleepMinutes: null,
    sleepScore: null,
    gainedXp: 0,
    startedAt: null,
    endedAt: null,
    ...overrides,
  }
}

export function character(overrides: Partial<Character> = {}): Character {
  return {
    level: 4,
    currentXp: 30,
    totalXp: 1_230,
    nextLevelXp: 120,
    stats: { str: 11, vit: 12, intStat: 13, discipline: 14, recovery: 15 },
    ...overrides,
  }
}
