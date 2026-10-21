import { describe, expect, it } from 'vitest';
import { MULTIPLE_FORWARDED_LINES_KEY, normalizeIp, resolveClientIp } from '../../src/http/client-ip.js';
import { FixedWindowRateLimiter, MemoryRateLimitStore, WINDOW_MS, type RateLimitStore } from '../../src/http/rate-limit.js';
import { BoundedExpiringStore } from '../../src/lib/bounded-store.js';
import { MutableClock } from '../support/fixtures.js';

describe('FixedWindowRateLimiter', () => {
  const setup = (maxKeys = 1000) => {
    const clock = new MutableClock();
    const limiter = new FixedWindowRateLimiter(new MemoryRateLimitStore(clock.fn, maxKeys), clock.fn);
    return { clock, limiter };
  };

  it('한도까지 허용하고 다음 요청부터 거부하며, Retry-After 는 윈도우 끝까지 남은 초(올림, 최소 1)', async () => {
    const { clock, limiter } = setup();
    for (let i = 0; i < 3; i++) expect((await limiter.tryAcquire('k', 3)).allowed).toBe(true);
    expect(await limiter.tryAcquire('k', 3)).toEqual({ allowed: false, retryAfterSeconds: 60 });
    clock.advance(30_500);
    expect(await limiter.tryAcquire('k', 3)).toEqual({ allowed: false, retryAfterSeconds: 30 }); // 29.5초 → 올림
    clock.advance(29_499);
    expect(await limiter.tryAcquire('k', 3)).toEqual({ allowed: false, retryAfterSeconds: 1 }); // 0.001초 남아도 1
  });

  it('윈도우가 끝나는 순간(정확히 60초) 카운터가 새로 시작한다', async () => {
    const { clock, limiter } = setup();
    expect((await limiter.tryAcquire('k', 1)).allowed).toBe(true);
    expect((await limiter.tryAcquire('k', 1)).allowed).toBe(false);
    clock.advance(WINDOW_MS - 1);
    expect((await limiter.tryAcquire('k', 1)).allowed).toBe(false);
    clock.advance(1);
    expect((await limiter.tryAcquire('k', 1)).allowed).toBe(true);
  });

  it('윈도우는 첫 요청 시각 기준이고 거부된 요청이 윈도우를 늘리지 않는다', async () => {
    const { clock, limiter } = setup();
    await limiter.tryAcquire('k', 1);
    clock.advance(59_000);
    for (let i = 0; i < 100; i++) await limiter.tryAcquire('k', 1);
    clock.advance(1000);
    expect((await limiter.tryAcquire('k', 1)).allowed).toBe(true);
  });

  it('한도 0 이하는 비활성(저장소를 건드리지 않음), 키는 서로 독립', async () => {
    let calls = 0;
    const store: RateLimitStore = { increment: async () => (calls++, { count: 999, windowEndMs: 0 }) };
    const limiter = new FixedWindowRateLimiter(store, () => 0);
    expect(await limiter.tryAcquire('k', 0)).toEqual({ allowed: true, retryAfterSeconds: 0 });
    expect((await limiter.tryAcquire('k', -5)).allowed).toBe(true);
    expect(calls).toBe(0);

    const { limiter: real } = setup();
    expect((await real.tryAcquire('a', 1)).allowed).toBe(true);
    expect((await real.tryAcquire('b', 1)).allowed).toBe(true);
    expect((await real.tryAcquire('a', 1)).allowed).toBe(false);
  });

  it('동시 호출도 정확히 한도만큼만 통과(단일 스레드 compute 의 원자성)', async () => {
    const { limiter } = setup();
    const results = await Promise.all(Array.from({ length: 50 }, () => limiter.tryAcquire('k', 10)));
    expect(results.filter((r) => r.allowed)).toHaveLength(10);
  });

  it('키 수 상한: 가득 차면 오래된 키부터 내보내 메모리가 늘지 않는다', async () => {
    const { clock, limiter } = setup(100);
    for (let i = 0; i < 1000; i++) {
      clock.advance(1);
      await limiter.tryAcquire(`ip-${i}`, 5);
    }
    // 가장 먼저 들어온 키는 내보내져 카운터가 새로 시작한다(제한이 풀리는 대신 메모리를 지킨다)
    const store = (limiter as unknown as { store: MemoryRateLimitStore }).store as unknown as { buckets: BoundedExpiringStore<unknown> };
    expect(store.buckets.size).toBeLessThanOrEqual(100);
  });
});

describe('BoundedExpiringStore', () => {
  type V = { exp: number; pinned?: boolean };
  const make = (clock: MutableClock, maxKeys: number, purge = 60_000) =>
    new BoundedExpiringStore<V>(clock.fn, purge, maxKeys, (v) => v.exp, (v) => !!v.pinned);

  it('생성자는 0 이하의 간격·상한을 거부', () => {
    const clock = new MutableClock();
    expect(() => new BoundedExpiringStore(clock.fn, 0, 1, () => 0)).toThrow();
    expect(() => new BoundedExpiringStore(clock.fn, 1, 0, () => 0)).toThrow();
  });

  it('만료 정리는 purge 간격마다 한 번(요청 경로에서)만 수행한다', () => {
    const clock = new MutableClock();
    const store = make(clock, 100, 1000);
    store.compute('a', () => ({ exp: clock.now + 500 }));
    clock.advance(999);
    expect(store.size).toBe(1); // 아직 정리 시점 전이라 만료됐어도 남아 있다
    expect(store.get('a')).toBeDefined();
    clock.advance(1);
    expect(store.get('a')).toBeUndefined(); // 간격 도달 → 정리
    expect(store.size).toBe(0);
  });

  it('remapping 이 undefined 를 돌려주면 삭제한다', () => {
    const clock = new MutableClock();
    const store = make(clock, 10);
    store.compute('a', () => ({ exp: 1 }));
    expect(store.compute('a', () => undefined)).toBeUndefined();
    expect(store.size).toBe(0);
    store.compute('b', () => ({ exp: 1 }));
    store.delete('b');
    expect(store.get('b')).toBeUndefined();
  });

  it('가득 차면 만료 항목을 먼저 지우고, 그래도 가득이면 가장 일찍 만료되는 것부터 내보낸다', () => {
    const clock = new MutableClock();
    const store = make(clock, 3, 10 * 60_000);
    store.compute('x', () => ({ exp: clock.now + 10 }));
    store.compute('y', () => ({ exp: clock.now + 20 }));
    store.compute('z', () => ({ exp: clock.now + 30 }));
    clock.advance(15); // x 만 만료
    store.compute('n1', () => ({ exp: clock.now + 1000 }));
    expect(store.get('x')).toBeUndefined();
    expect(store.get('y')).toBeDefined();
    // 이제 y, z, n1 이 모두 유효한 채로 가득: 새 키가 오면 가장 일찍 만료되는 y 가 나간다
    store.compute('n2', () => ({ exp: clock.now + 1000 }));
    expect(store.get('y')).toBeUndefined();
    expect(['z', 'n1', 'n2'].every((k) => store.get(k) !== undefined)).toBe(true);
  });

  it('상한이 크면 한 번에 상한의 1% 이상을 내보낸다(매 요청 축출 방지)', () => {
    const clock = new MutableClock();
    const store = make(clock, 200, 10 * 60_000);
    for (let i = 0; i < 200; i++) store.compute(`k${i}`, () => ({ exp: clock.now + 1000 + i }));
    store.compute('new', () => ({ exp: clock.now + 5000 }));
    expect(store.size).toBe(199); // 2개 내보내고 1개 추가
    expect(store.get('k0')).toBeUndefined();
    expect(store.get('k1')).toBeUndefined();
    expect(store.get('k2')).toBeDefined();
  });

  it('만료 시각이 바뀐 항목은 축출 순서에서 뒤로 간다', () => {
    const clock = new MutableClock();
    const store = make(clock, 3, 10 * 60_000);
    store.compute('a', () => ({ exp: clock.now + 100 }));
    store.compute('b', () => ({ exp: clock.now + 200 }));
    store.compute('c', () => ({ exp: clock.now + 300 }));
    store.compute('a', () => ({ exp: clock.now + 900 })); // a 가 가장 늦게 만료
    store.compute('d', () => ({ exp: clock.now + 400 }));
    expect(store.get('b')).toBeUndefined();
    expect(store.get('a')).toBeDefined();
  });

  it('고정(pinned) 항목은 축출하지 않고, 전부 고정이면 상한을 잠시 넘는다', () => {
    const clock = new MutableClock();
    const store = make(clock, 2, 10 * 60_000);
    store.compute('p1', () => ({ exp: clock.now + 1000, pinned: true }));
    store.compute('p2', () => ({ exp: clock.now + 1000, pinned: true }));
    store.compute('p3', () => ({ exp: clock.now + 1000, pinned: true }));
    expect(store.size).toBe(3);
    expect(['p1', 'p2', 'p3'].every((k) => store.get(k) !== undefined)).toBe(true);
  });
});

describe('클라이언트 IP 경계', () => {
  const node = { platform: 'node' as const, trustForwardedHeader: true, trustedProxyHops: 0, remoteAddress: '10.0.0.9' };

  it.each([
    ['0.0.0.0', '0.0.0.0'],
    ['255.255.255.255', '255.255.255.255'],
    ['256.0.0.1', null],
    ['1.2.3', null],
    ['1.2.3.4.5', null],
    ['01.2.3.4', '01.2.3.4'],
    ['::', '0:0:0:0::/64'],
    ['::1', '0:0:0:0::/64'],
    ['fe80::1%eth0', null],
    ['2001:db8::1::2', null],
    ['2001:db8:0:0:0:0:0:1:5', null],
    ['2001:db8:g::1', null],
    ['12345::1', null],
    ['::ffff:1.2.3.4', '1.2.3.4'],
    ['::ffff:0102:0304', '1.2.3.4'],
    ['::ffff:1.2.3.999', null],
    ['2001:DB8:A::1', '2001:db8:a:0::/64'],
    ['[::ffff:1.2.3.4]', '1.2.3.4'],
    ['  1.2.3.4  ', '1.2.3.4'],
    ['', null],
    [null, null],
    [undefined, null],
    ['1.2.3.4:8080', null],
  ])('normalizeIp(%j) → %j', (input, expected) => {
    expect(normalizeIp(input)).toBe(expected);
  });

  it('같은 /64 의 IPv6 는 같은 키, 다른 /64 는 다른 키', () => {
    expect(normalizeIp('2001:db8:1:2:aaaa::1')).toBe(normalizeIp('2001:db8:1:2:bbbb::2'));
    expect(normalizeIp('2001:db8:1:2::1')).not.toBe(normalizeIp('2001:db8:1:3::1'));
  });

  it('64자를 넘는 값은 버리고, 소켓 주소도 IP 가 아니면 잘라서(최대 64자) 쓴다', () => {
    expect(normalizeIp('1'.repeat(65))).toBeNull();
    expect(resolveClientIp({ ...node, forwardedForLines: ['9'.repeat(70)] })).toBe('10.0.0.9');
    expect(resolveClientIp({ ...node, remoteAddress: 'weird-socket-'.repeat(10), forwardedForLines: [] })).toHaveLength(64);
    expect(resolveClientIp({ ...node, remoteAddress: '  ', forwardedForLines: [] })).toBe('unknown');
    expect(resolveClientIp({ ...node, remoteAddress: null, forwardedForLines: [] })).toBe('unknown');
    expect(resolveClientIp({ ...node, remoteAddress: undefined })).toBe('unknown');
  });

  it('X-Forwarded-For 의 값이 IP 가 아니면(위조) 소켓 주소로 돌아간다', () => {
    expect(resolveClientIp({ ...node, forwardedForLines: ['not-an-ip'] })).toBe('10.0.0.9');
    expect(resolveClientIp({ ...node, forwardedForLines: ['1.1.1.1, evil'] })).toBe('10.0.0.9');
    expect(resolveClientIp({ ...node, forwardedForLines: [''] })).toBe('10.0.0.9');
    expect(resolveClientIp({ ...node, forwardedForLines: [] })).toBe('10.0.0.9');
  });

  it('hops 가 음수면 헤더를 쓰지 않고, 공백이 낀 목록도 오른쪽 기준으로 자른다', () => {
    expect(resolveClientIp({ ...node, trustedProxyHops: -1, forwardedForLines: ['1.1.1.1'] })).toBe('10.0.0.9');
    expect(resolveClientIp({ ...node, trustedProxyHops: 1, forwardedForLines: ['2.2.2.2 ,  1.1.1.1 , 10.0.0.1'] })).toBe('1.1.1.1');
  });

  it('여러 줄 헤더는 신뢰하지 않을 때에는 영향이 없고, 신뢰할 때는 줄 수와 관계없이 하나의 공용 키', () => {
    expect(resolveClientIp({ ...node, trustForwardedHeader: false, forwardedForLines: ['1.1.1.1', '2.2.2.2'] })).toBe('10.0.0.9');
    expect(resolveClientIp({ ...node, forwardedForLines: ['1.1.1.1', '2.2.2.2', '3.3.3.3'] })).toBe(MULTIPLE_FORWARDED_LINES_KEY);
  });

  it('Workers: CF-Connecting-IP 가 없거나 이상하면 소켓 주소, X-Forwarded-For 는 무시', () => {
    const workers = { ...node, platform: 'workers' as const };
    expect(resolveClientIp({ ...workers, cfConnectingIp: null, forwardedForLines: ['6.6.6.6'] })).toBe('10.0.0.9');
    expect(resolveClientIp({ ...workers, cfConnectingIp: 'garbage' })).toBe('10.0.0.9');
    expect(resolveClientIp({ ...workers, cfConnectingIp: '2001:db8::7', remoteAddress: null })).toBe('2001:db8:0:0::/64');
  });
});
