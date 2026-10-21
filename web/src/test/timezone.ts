import { afterAll, beforeAll } from 'vitest'

type ProcessEnv = Record<string, string | undefined>

/** tsconfig가 @types/node를 쓰지 않으므로 process는 globalThis로 읽는다(csp.ts와 같은 방식). */
const env = (): ProcessEnv => (globalThis as unknown as { process: { env: ProcessEnv } }).process.env

/**
 * 이 파일의 테스트 동안 Node 프로세스 시간대를 바꾼다. Node는 process.env.TZ 대입을 즉시 반영한다.
 * 테스트 파일은 각자 격리된 워커에서 돌므로 다른 파일에 영향이 없고, 끝나면 원래 값으로 되돌린다.
 */
export function useTimeZone(timeZone: string): void {
  let previous: string | undefined
  beforeAll(() => {
    previous = env().TZ
    env().TZ = timeZone
  })
  afterAll(() => {
    if (previous === undefined) {
      delete env().TZ
    } else {
      env().TZ = previous
    }
  })
}

/** 한국 표준시 기준으로 UTC 날짜가 아직 전날인 시각(KST 2026-10-22 01:30 = UTC 2026-10-21 16:30). */
export const KST_EARLY_MORNING = new Date('2026-10-21T16:30:00Z')
/** 한국 표준시 정오(UTC와 날짜가 같다). */
export const KST_NOON = new Date('2026-10-22T03:00:00Z')
