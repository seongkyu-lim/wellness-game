import { afterAll, beforeAll, beforeEach, expect } from 'vitest';
import type { Db } from '../../src/db/client.js';
import type { FetchFn } from '../../src/auth/http-client.js';
import { buildTestApp, openDb, resetSchema } from './api.js';
import { MutableClock, T0 } from './fixtures.js';

export type Api = ReturnType<typeof buildTestApp>;
export type Call = Api['call'];

/** 실제 PostgreSQL 위에서 HTTP 앱을 테스트마다 새 스키마로 세운다(describeDb 안에서 호출) */
export function useApi(options: { env?: Record<string, string>; fetch?: () => FetchFn | undefined } = {}) {
  let db: Db;
  let clock: MutableClock;
  beforeAll(() => {
    db = openDb();
  });
  afterAll(async () => {
    await db?.end();
  });
  beforeEach(async () => {
    await resetSchema(db);
    clock = new MutableClock(T0);
  });
  return {
    get db() {
      return db;
    },
    get clock() {
      return clock;
    },
    /** 앱을 새로 만든다(인메모리 상태 — 레이트 리밋·캐시·잠금 — 가 비워진다) */
    build: (env?: Record<string, string>): Api => buildTestApp({ db, clock, fetch: options.fetch?.(), env: { ...options.env, ...env } }),
  };
}

export async function signUp(call: Call, username = 'tester_01', password = 'password123') {
  const res = await call('POST', '/api/auth/signup', { body: { username, password, displayName: '테스터' } });
  expect(res.status).toBe(200);
  return res.json as {
    userIdentifier: string;
    userId: string;
    accessToken: string;
    refreshToken: string;
    refreshExpiresIn: number;
    expiresIn: number;
  };
}

/** 서울 2026-10-20 의 걸음 수 동기화 본문 */
export const stepsBody = (steps: number, date = '2026-10-20') => ({ date, activities: [{ type: 'STEPS', steps }] });

/** 서울 2026-10-20 에 속하는 한 시간짜리 운동(15:00Z 기준 오프셋 분). calories 로 XP 상한(200)에 닿게 한다 */
export function workoutAt(offsetMinutes: number, o: { minutes?: number; type?: string; calories?: number } = {}) {
  const minutes = o.minutes ?? 60;
  const start = Date.parse('2026-10-19T15:00:00Z') + offsetMinutes * 60_000;
  return {
    type: 'WORKOUT',
    workoutType: o.type ?? 'CYCLING',
    calories: o.calories ?? 100,
    startedAt: new Date(start).toISOString().replace('.000Z', 'Z'),
    endedAt: new Date(start + minutes * 60_000).toISOString().replace('.000Z', 'Z'),
  };
}
