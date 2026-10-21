import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import worker, { type WorkerEnv } from '../../src/index.js';
import { scheduleDailyCleanup } from '../../src/scheduled.js';
import type { Services } from '../../src/services.js';
import { quietLogs, TEST_SECRET } from '../support/fixtures.js';

const ctx = { waitUntil: () => {}, passThroughOnException: () => {}, props: {} } as unknown as Parameters<typeof worker.fetch>[2];
const req = (path: string, init?: RequestInit) => new Request(`https://api.example.test${path}`, init);

describe('Workers 진입점(index.ts)', () => {
  quietLogs();
  const env = (extra: Record<string, unknown> = {}): WorkerEnv => ({ AUTH_JWT_SECRET: TEST_SECRET, ...extra });

  it('설정이 올바르면 DB 없이도 /healthz 에 응답하고 보안 헤더가 붙는다', async () => {
    const res = await worker.fetch(req('/healthz'), env(), ctx);
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ status: 'UP' });
    expect(res.headers.get('x-content-type-options')).toBe('nosniff');
  });

  it('설정 오류(시크릿 누락)는 503 {"status":"DOWN"} 만 내보내고 상세는 응답에 없다', async () => {
    const res = await worker.fetch(req('/healthz'), { AUTH_JWT_SECRET: 'short' }, ctx);
    expect(res.status).toBe(503);
    expect(res.headers.get('content-type')).toBe('application/json');
    const text = await res.text();
    expect(JSON.parse(text)).toEqual({ status: 'DOWN' });
    expect(text).not.toMatch(/AUTH_JWT_SECRET|바이트/);
  });

  it('같은 env 객체에서는 서비스(인메모리 레이트 리밋)를 재사용하고, env 가 바뀌면 새로 만든다', async () => {
    const shared = env({ APP_RATE_LIMIT_LOGIN_PER_MINUTE: '1' });
    const login = (e: WorkerEnv, ip: string) =>
      worker.fetch(
        req('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': ip }, body: '{}' }),
        e,
        ctx,
      );
    expect((await login(shared, '203.0.113.1')).status).toBe(400);
    expect((await login(shared, '203.0.113.1')).status).toBe(429); // 같은 isolate·같은 IP
    expect((await login(shared, '203.0.113.2')).status).toBe(400); // IP 가 다르면 별도 버킷(CF-Connecting-IP)
    expect((await login(env({ APP_RATE_LIMIT_LOGIN_PER_MINUTE: '1' }), '203.0.113.1')).status).toBe(400); // 새 env → 새 서비스
  });

  it('Workers 에서는 X-Forwarded-For 를 믿지 않는다(CF-Connecting-IP 만)', async () => {
    const e = env({ APP_RATE_LIMIT_LOGIN_PER_MINUTE: '1', APP_RATE_LIMIT_TRUST_FORWARDED_HEADER: 'true' });
    const login = (xff: string) =>
      worker.fetch(
        req('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': '198.51.100.9', 'X-Forwarded-For': xff }, body: '{}' }),
        e,
        ctx,
      );
    expect((await login('1.1.1.1')).status).toBe(400);
    expect((await login('2.2.2.2')).status).toBe(429);
  });
});

describe('매일 정리 스케줄(scheduleDailyCleanup)', () => {
  quietLogs();
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  /** 가짜 타이머가 흘려 주는 시각을 서비스 시계로 쓰는 가짜 서비스(시작 시각을 고정) */
  function fake(startIso: string, purge: () => Promise<number> = async () => 0) {
    vi.setSystemTime(Date.parse(startIso));
    const services = {
      config: { timezone: 'Asia/Seoul' },
      clock: () => Date.now(),
      refresh: { purgeExpiredAndRevoked: vi.fn(async () => purge()) },
    } as unknown as Services;
    return { services, purge: services.refresh.purgeExpiredAndRevoked as ReturnType<typeof vi.fn> };
  }

  it('오늘 04:00(서울) 전이면 오늘, 지났으면 내일 04:00 에 실행하고 매일 반복한다', async () => {
    // 2026-10-20 03:00 서울(= 10-19T18:00Z) → 1시간 뒤
    const { services, purge } = fake('2026-10-19T18:00:00Z');
    const job = scheduleDailyCleanup(services, {} as never);
    await vi.advanceTimersByTimeAsync(3_600_000 - 1);
    expect(purge).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(purge).toHaveBeenCalledTimes(1);
    // 방금 04:00 였으므로 다음은 24시간 뒤
    await vi.advanceTimersByTimeAsync(24 * 3_600_000 - 1);
    expect(purge).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(purge).toHaveBeenCalledTimes(2);
    job.stop();
  });

  it('정확히 04:00 에 시작하면 지난 시각으로 보고 내일로 넘긴다(즉시 실행하지 않음)', async () => {
    const { services, purge } = fake('2026-10-19T19:00:00Z'); // 서울 04:00 정각
    const job = scheduleDailyCleanup(services, {} as never);
    await vi.advanceTimersByTimeAsync(23 * 3_600_000);
    expect(purge).not.toHaveBeenCalled();
    job.stop();
  });

  it('정리가 실패해도 오류만 남기고 다음 스케줄은 계속된다', async () => {
    const { services, purge } = fake('2026-10-19T18:00:00Z', async () => {
      throw new Error('db down');
    });
    const job = scheduleDailyCleanup(services, {} as never);
    await vi.advanceTimersByTimeAsync(3_600_000);
    expect(purge).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(24 * 3_600_000);
    expect(purge).toHaveBeenCalledTimes(2);
    job.stop();
  });

  it('stop() 뒤에는 실행하지 않는다', async () => {
    const { services, purge } = fake('2026-10-19T18:00:00Z');
    const job = scheduleDailyCleanup(services, {} as never);
    job.stop();
    await vi.advanceTimersByTimeAsync(48 * 3_600_000);
    expect(purge).not.toHaveBeenCalled();
  });
});
