import { describe, expect, it } from 'vitest';
import { LoginAttemptGuard } from '../../src/auth/login-guard.js';
import { asLong } from '../../src/auth/http-client.js';
import { toResult } from '../../src/auth/social.js';
import { ApiError } from '../../src/lib/errors.js';
import { BoundedExpiringStore } from '../../src/lib/bounded-store.js';
import type { Db } from '../../src/db/client.js';
import { buildTestApp } from '../support/api.js';
import { MutableClock, testConfig } from '../support/fixtures.js';

const noDb = new Proxy({}, {
  get() {
    throw new Error('DB 를 쓰면 안 되는 테스트입니다.');
  },
}) as unknown as Db;

function app() {
  const built = buildTestApp({ db: noDb });
  built.services.validity.isRevoked = async () => false;
  return built;
}

describe('설정 거부', () => {
  it('예시 문구가 든 JWT 시크릿은 기동 실패', () => {
    expect(() => testConfig({ AUTH_JWT_SECRET: 'local-dev-only-jwt-secret-0123456789abcdef' })).toThrow(/예시 값/);
    expect(() => testConfig({ AUTH_JWT_SECRET: 'prod-secret-CHANGE-ME-0123456789abcdefghij' })).toThrow(/예시 값/);
  });

  it('네이티브 구글 클라이언트 ID 목록에 웹 클라이언트 ID 가 있으면 기동 실패', () => {
    expect(() => testConfig({ OAUTH_GOOGLE_NATIVE_CLIENT_IDS: 'ios-client,web-client.apps.googleusercontent.com' })).toThrow(
      /OAUTH_GOOGLE_CLIENT_ID/,
    );
  });
});

describe('본문 크기·CORS·캐시', () => {
  it('인증 경로 16KB 초과 → 413(Content-Length 있음·없음 모두)', async () => {
    const { app: hono } = app();
    const big = JSON.stringify({ username: 'a'.repeat(17 * 1024), password: 'x' });
    const withLength = await hono.request('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': String(big.length) },
      body: big,
    });
    expect(withLength.status).toBe(413);
    expect(await withLength.json()).toMatchObject({ status: 413, error: 'Content Too Large', path: '/api/auth/login' });

    const stream = new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(big));
        controller.close();
      },
    });
    const chunked = await hono.request('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: stream,
      duplex: 'half',
    } as RequestInit);
    expect(chunked.status).toBe(413);
  });

  it('동기화 256KB 초과 → 413, 활동 개수 초과는 항목을 읽기 전에 400', async () => {
    const { call, services } = app();
    const token = (await services.jwt.issue('kakao:1')).accessToken;
    const huge = { date: '2026-10-20', activities: [{ type: 'STEPS', steps: 1, pad: 'x'.repeat(300 * 1024) }] };
    expect((await call('POST', '/api/health-activities/sync', { token, body: huge })).status).toBe(413);
    // 51번째 항목이 형식 오류여도 개수 위반이 먼저다
    const activities: unknown[] = Array.from({ length: 50 }, () => ({ type: 'STEPS', steps: 1 }));
    activities.push('not-an-object');
    const res = await call('POST', '/api/health-activities/sync', { token, body: { date: '2026-10-20', activities } });
    expect(res.json.message).toBe('activities: 한 번에 최대 50개의 활동까지 동기화할 수 있습니다.');
  });

  it('허용 목록에 없는 Origin 은 preflight·실제 요청 모두 403 "Invalid CORS request"', async () => {
    const { call } = app();
    const preflight = await call('OPTIONS', '/api/auth/login', {
      headers: { Origin: 'https://evil.example', 'Access-Control-Request-Method': 'POST' },
    });
    expect(preflight.status).toBe(403);
    expect(preflight.json).toBe('Invalid CORS request');
    const actual = await call('GET', '/api/characters/me', { headers: { Origin: 'https://evil.example' } });
    expect(actual.status).toBe(403);
    // 같은 출처(Origin = 요청 주소)는 CORS 요청이 아니다
    expect((await call('GET', '/api/characters/me', { headers: { Origin: 'http://localhost' } })).status).toBe(401);
  });

  it('인증 응답은 Cache-Control: no-store', async () => {
    const { call } = app();
    const res = await call('POST', '/api/auth/login', { body: { username: '', password: '' } });
    expect(res.headers.get('cache-control')).toBe('no-store');
  });

  it('date 쿼리는 trim 하지 않는다(앞뒤 공백은 형식 오류, 공백만이면 누락)', async () => {
    const { call, services } = app();
    const token = (await services.jwt.issue('u')).accessToken;
    expect((await call('GET', '/api/health-activities?date=%202026-10-20', { token })).json.message).toBe(
      '요청 값의 형식이 올바르지 않습니다.',
    );
    expect((await call('GET', '/api/health-activities?date=%20', { token })).json.message).toBe('필수 요청 값이 빠졌습니다.');
  });
});

describe('잠금 기록 축출', () => {
  it('키가 가득 차도 잠금 중인(고정) 항목은 내보내지 않는다', () => {
    const clock = new MutableClock();
    const store = new BoundedExpiringStore<{ n: number; exp: number }>(clock.fn, 60_000, 3, (v) => v.exp, (v) => v.n >= 5);
    store.compute('locked', () => ({ n: 5, exp: clock.now + 1000 }));
    for (let i = 0; i < 10; i++) {
      clock.advance(1);
      store.compute(`k${i}`, () => ({ n: 1, exp: clock.now + 1000 }));
    }
    expect(store.get('locked')).toBeDefined();
    expect(store.get('k9')).toBeDefined();
    expect(store.size).toBeLessThanOrEqual(3);
  });

  it('LoginAttemptGuard: 다른 키를 대량으로 만들어도 잠금이 풀리지 않는다', () => {
    const clock = new MutableClock();
    const guard = new LoginAttemptGuard({ maxFailedLogins: 2, maxFailedLoginsPerUsername: 0, lockoutMinutes: 15 }, clock.fn, 5);
    expect(guard.tryReserve('victim', '1.1.1.1')).toBe(true);
    expect(guard.tryReserve('victim', '1.1.1.1')).toBe(true);
    expect(guard.tryReserve('victim', '1.1.1.1')).toBe(false);
    for (let i = 0; i < 50; i++) guard.tryReserve(`user${i}`, '2.2.2.2');
    expect(guard.tryReserve('victim', '1.1.1.1')).toBe(false);
  });
});

describe('회원번호 정수 안전성', () => {
  it('2^53 이상 회원번호는 0(무효)으로 보고 카카오 로그인을 거부한다', () => {
    expect(asLong(12345)).toBe(12345);
    expect(asLong('12345')).toBe(12345);
    expect(asLong(2 ** 53)).toBe(0);
    expect(asLong('9007199254740993')).toBe(0);
    expect(() => toResult('kakao', { id: 2 ** 53 + 2 })).toThrow(ApiError);
    expect(toResult('kakao', { id: 42, properties: { nickname: 'n' } })).toEqual({ userId: 'kakao:42', displayName: 'n' });
  });
});
