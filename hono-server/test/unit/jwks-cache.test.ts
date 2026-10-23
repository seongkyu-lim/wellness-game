import { beforeAll, describe, expect, it } from 'vitest';
import { HttpClient } from '../../src/auth/http-client.js';
import { FAILURE_BACKOFF_MS, JwksKeyCache, MIN_REFRESH_INTERVAL_MS } from '../../src/auth/jwks-cache.js';
import { quietLogs, T0 } from '../support/fixtures.js';
import { jwksOf, makeKey, type TestKey } from '../support/oauth.js';

const TTL = 3_600_000;
const STALE_MAX = 86_400_000;

let a: TestKey;
let b: TestKey;
beforeAll(async () => {
  a = await makeKey('kid-a');
  b = await makeKey('kid-b');
});

/** 응답을 시나리오로 바꿔 가며 JWKS 요청 횟수를 세는 캐시 */
function setup() {
  const state = { now: T0, fetches: 0, mode: 'ok' as 'ok' | 'fail' | 'bad-json' | 'no-keys' | 'no-rsa' | 'bad-key' | 'gate', keys: [a] as TestKey[], gate: null as null | Promise<void> };
  const fetchFn = async () => {
    state.fetches++;
    switch (state.mode) {
      case 'fail':
        return new Response('', { status: 503 });
      case 'bad-json':
        return new Response('not json');
      case 'no-keys':
        return Response.json({ nokeys: true });
      case 'no-rsa':
        return Response.json({ keys: [{ kty: 'EC', kid: 'e', crv: 'P-256', x: 'a', y: 'b' }, { kty: 'RSA' }] });
      case 'bad-key':
        return Response.json({ keys: [{ kty: 'RSA', kid: 'broken' }] });
      case 'gate':
        await state.gate;
        return Response.json(jwksOf(...state.keys));
      default:
        return Response.json(jwksOf(...state.keys));
    }
  };
  const cache = new JwksKeyCache('Test', new HttpClient(fetchFn, 1000), 'https://jwks.test/keys', TTL, STALE_MAX, () => state.now);
  return { state, cache, advance: (ms: number) => (state.now += ms) };
}

const header = (kid?: string, alg = 'RS256') => ({ alg, kid });

describe('JwksKeyCache', () => {
  quietLogs();

  it('alg 가 RS256 이 아니거나 kid 가 비어 있으면 JWKS 를 받지 않고 거부한다', async () => {
    const { cache, state } = setup();
    await expect(cache.keyFor(header('kid-a', 'HS256'))).rejects.toMatchObject({ name: 'JwksKeyError' });
    await expect(cache.keyFor(header(undefined))).rejects.toMatchObject({ name: 'JwksKeyError' });
    await expect(cache.keyFor(header('  '))).rejects.toMatchObject({ name: 'JwksKeyError' });
    expect(state.fetches).toBe(0);
  });

  it('캐시 TTL 안에서는 다시 받지 않고, TTL 이 지나면 한 번 다시 받는다', async () => {
    const { cache, state, advance } = setup();
    await cache.keyFor(header('kid-a'));
    advance(TTL - 1);
    await cache.keyFor(header('kid-a'));
    expect(state.fetches).toBe(1);
    advance(1); // 정확히 TTL
    await cache.keyFor(header('kid-a'));
    expect(state.fetches).toBe(2);
  });

  it('모르는 kid 는 1분 안에는 재조회하지 않는다(위조 kid 반복 호출 방지), 1분이 지나면 키 교체를 따라간다', async () => {
    const { cache, state, advance } = setup();
    await cache.keyFor(header('kid-a'));
    for (let i = 0; i < 20; i++) await expect(cache.keyFor(header('kid-b'))).rejects.toMatchObject({ name: 'JwksKeyError' });
    expect(state.fetches).toBe(1);

    state.keys = [b]; // 키 교체
    advance(MIN_REFRESH_INTERVAL_MS - 1);
    await expect(cache.keyFor(header('kid-b'))).rejects.toMatchObject({ name: 'JwksKeyError' });
    expect(state.fetches).toBe(1);
    advance(1);
    await expect(cache.keyFor(header('kid-b'))).resolves.toBeInstanceOf(CryptoKey);
    expect(state.fetches).toBe(2);
    // 교체 뒤 옛 키는 없다
    advance(MIN_REFRESH_INTERVAL_MS);
    await expect(cache.keyFor(header('kid-a'))).rejects.toMatchObject({ name: 'JwksKeyError' });
  });

  it('재조회가 실패하면 30초 동안 다시 시도하지 않고 기존 키를 계속 쓴다', async () => {
    const { cache, state, advance } = setup();
    await cache.keyFor(header('kid-a'));
    advance(TTL);
    state.mode = 'fail';
    await expect(cache.keyFor(header('kid-a'))).resolves.toBeInstanceOf(CryptoKey);
    expect(state.fetches).toBe(2);
    advance(FAILURE_BACKOFF_MS - 1);
    await expect(cache.keyFor(header('kid-a'))).resolves.toBeInstanceOf(CryptoKey);
    expect(state.fetches).toBe(2); // 백오프 중
    state.mode = 'ok';
    advance(1);
    await expect(cache.keyFor(header('kid-a'))).resolves.toBeInstanceOf(CryptoKey);
    expect(state.fetches).toBe(3); // 백오프 끝, 복구
    advance(TTL - 1);
    await cache.keyFor(header('kid-a'));
    expect(state.fetches).toBe(3); // 복구 시점부터 새 TTL
  });

  it('마지막 성공 조회가 stale-max 보다 오래되면 실패를 503 용 오류로 올린다(경계: 정확히 stale-max 는 허용)', async () => {
    const { cache, state, advance } = setup();
    await cache.keyFor(header('kid-a'));
    state.mode = 'fail';
    advance(STALE_MAX);
    await expect(cache.keyFor(header('kid-a'))).resolves.toBeInstanceOf(CryptoKey);
    advance(FAILURE_BACKOFF_MS); // 백오프가 끝나 다시 시도하지만 실패하고, 이제 stale-max 를 1ms 넘김
    await expect(cache.keyFor(header('kid-a'))).rejects.toMatchObject({ name: 'JwksUnavailableError' });
  });

  it('기존 키가 없을 때 실패하면 JwksUnavailableError, 백오프 중에는 요청 없이 같은 오류', async () => {
    const { cache, state, advance } = setup();
    state.mode = 'fail';
    await expect(cache.keyFor(header('kid-a'))).rejects.toMatchObject({ name: 'JwksUnavailableError', message: expect.stringContaining('조회 실패') });
    await expect(cache.keyFor(header('kid-a'))).rejects.toMatchObject({ name: 'JwksUnavailableError', message: expect.stringContaining('재시도 대기') });
    expect(state.fetches).toBe(1);
    advance(FAILURE_BACKOFF_MS);
    state.mode = 'ok';
    await expect(cache.keyFor(header('kid-a'))).resolves.toBeInstanceOf(CryptoKey);
  });

  it.each(['bad-json', 'no-keys', 'no-rsa', 'bad-key'] as const)('JWKS 본문이 비정상(%s)이면 키를 캐시하지 않고 실패로 본다', async (mode) => {
    const { cache, state } = setup();
    state.mode = mode;
    await expect(cache.keyFor(header('kid-a'))).rejects.toMatchObject({ name: 'JwksUnavailableError' });
  });

  it('kid 없는 키·RSA 가 아닌 키는 건너뛰고 나머지를 쓴다', async () => {
    const { cache, state } = setup();
    state.keys = [a, b];
    await expect(cache.keyFor(header('kid-b'))).resolves.toBeInstanceOf(CryptoKey);
  });

  it('다른 요청이 재조회 중이면 기존 키가 있는 요청은 기다리지 않고 기존 키로 검증한다(한 번만 받음)', async () => {
    const { cache, state, advance } = setup();
    await cache.keyFor(header('kid-a'));
    advance(TTL);
    let release!: () => void;
    state.gate = new Promise<void>((resolve) => (release = resolve));
    state.mode = 'gate';
    const first = cache.keyFor(header('kid-a')); // 재조회 시작(게이트에서 대기)
    await Promise.resolve();
    await expect(cache.keyFor(header('kid-a'))).resolves.toBeInstanceOf(CryptoKey);
    expect(state.fetches).toBe(2);
    release();
    await expect(first).resolves.toBeInstanceOf(CryptoKey);
    expect(state.fetches).toBe(2);
  });
});
