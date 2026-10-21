import { describe, expect, it } from 'vitest';
import type { Context } from 'hono';
import {
  UnsupportedMediaTypeError,
  decimal,
  enumOf,
  instant,
  int,
  list,
  listItem,
  localDate,
  maxUtf8Bytes,
  notBlank,
  notEmpty,
  notNull,
  pattern,
  positiveOrZero,
  readJsonObject,
  size,
  str,
  validate,
} from '../../src/http/body.js';
import { loadConfig, resolveDatabaseUrl } from '../../src/config.js';
import { ApiError, UnreadableBodyError, resolve } from '../../src/lib/errors.js';
import { TEST_SECRET } from '../support/fixtures.js';

const unreadable = (fn: () => unknown) => {
  try {
    fn();
  } catch (error) {
    if (error instanceof UnreadableBodyError) return error.message;
    throw error;
  }
  return null;
};
const violation = (check: () => ApiError | null, locale: 'ko' | 'en' = 'ko') => {
  const error = check();
  return error ? resolve(locale, error.localized) : null;
};

/** readJsonObject 가 쓰는 부분만 흉내 낸 컨텍스트 */
const ctx = (contentType: string | undefined, body: string) =>
  ({ req: { header: (name: string) => (name === 'content-type' ? contentType : undefined), text: async () => body } }) as unknown as Context;

describe('본문 읽기(readJsonObject)', () => {
  it('JSON 계열 Content-Type 만 허용(charset·+json 포함), 아니면 415', async () => {
    await expect(readJsonObject(ctx('application/json', '{"a":1}'))).resolves.toEqual({ a: 1 });
    await expect(readJsonObject(ctx('Application/JSON; charset=utf-8', '{}'))).resolves.toEqual({});
    await expect(readJsonObject(ctx('application/merge-patch+json', '{}'))).resolves.toEqual({});
    for (const type of [undefined, '', 'text/plain', 'application/x-www-form-urlencoded', 'application/jsonp', 'multipart/form-data']) {
      await expect(readJsonObject(ctx(type, '{}')), String(type)).rejects.toBeInstanceOf(UnsupportedMediaTypeError);
    }
  });

  it.each([
    ['빈 본문', ''],
    ['공백 본문', '  \n '],
    ['깨진 JSON', '{"a":'],
    ['배열', '[1,2]'],
    ['null', 'null'],
    ['문자열', '"text"'],
    ['숫자', '42'],
  ])('%s 은 400(UnreadableBody)', async (_name, body) => {
    await expect(readJsonObject(ctx('application/json', body))).rejects.toBeInstanceOf(UnreadableBodyError);
  });

  it('오류 상세에 입력 조각이 들어가지 않는다', async () => {
    const error = await readJsonObject(ctx('application/json', '{"password":"hunter2"')).catch((e: Error) => e);
    expect((error as Error).message).not.toContain('hunter2');
  });
});

describe('필드 역직렬화(Jackson 강제 변환)', () => {
  it('str: 숫자·불리언은 문자열로, 객체·배열은 오류, null/없음은 null', () => {
    expect(str({ a: 12 }, 'a')).toBe('12');
    expect(str({ a: true }, 'a')).toBe('true');
    expect(str({ a: null }, 'a')).toBeNull();
    expect(str({}, 'a')).toBeNull();
    expect(unreadable(() => str({ a: {} }, 'a'))).toBe("Cannot deserialize field 'a'");
    expect(unreadable(() => str({ a: [] }, 'a'))).not.toBeNull();
  });

  it('int: 소수는 버림, 숫자 문자열 허용, 빈 문자열은 null, int 범위 밖·NaN·불리언은 오류', () => {
    expect(int({ a: 3.9 }, 'a')).toBe(3);
    expect(int({ a: -3.9 }, 'a')).toBe(-3);
    expect(int({ a: ' 42 ' }, 'a')).toBe(42);
    expect(int({ a: '1e3' }, 'a')).toBe(1000);
    expect(int({ a: '' }, 'a')).toBeNull();
    expect(int({ a: '   ' }, 'a')).toBeNull();
    expect(int({ a: 2_147_483_647 }, 'a')).toBe(2_147_483_647);
    expect(int({ a: -2_147_483_648 }, 'a')).toBe(-2_147_483_648);
    for (const bad of [2_147_483_648, -2_147_483_649, 1e30, 'abc', '12abc', true, {}, [1]]) {
      expect(unreadable(() => int({ a: bad }, 'a')), JSON.stringify(bad)).not.toBeNull();
    }
    expect(unreadable(() => int({ a: 2_147_483_647.9 }, 'a'))).toBeNull(); // 버림 후 범위 안
  });

  it('decimal: 숫자·숫자 문자열, 빈 문자열은 null, 그 밖은 오류', () => {
    expect(decimal({ a: '12.50' }, 'a')).toBe(12.5);
    expect(decimal({ a: ' .5 ' }, 'a')).toBe(0.5);
    expect(decimal({ a: -1.25 }, 'a')).toBe(-1.25);
    expect(decimal({ a: '' }, 'a')).toBeNull();
    for (const bad of ['1,5', 'NaN', 'Infinity', true, [], {}]) {
      expect(unreadable(() => decimal({ a: bad }, 'a')), JSON.stringify(bad)).not.toBeNull();
    }
  });

  it('enumOf: 이름(대소문자 구분) 또는 서수, 빈 문자열은 null, 그 밖은 오류', () => {
    const kinds = ['A', 'B', 'C'] as const;
    expect(enumOf({ a: 'B' }, 'a', kinds)).toBe('B');
    expect(enumOf({ a: 2 }, 'a', kinds)).toBe('C');
    expect(enumOf({ a: '' }, 'a', kinds)).toBeNull();
    for (const bad of ['b', 'D', 3, -1, 1.5, true, [], {}]) {
      expect(unreadable(() => enumOf({ a: bad }, 'a', kinds)), JSON.stringify(bad)).not.toBeNull();
    }
  });

  it('localDate: 문자열(trim)·[y,m,d] 배열, 불가능한 날짜는 오류, 빈 문자열은 null', () => {
    expect(localDate({ a: ' 2026-10-20 ' }, 'a')).toBe('2026-10-20');
    expect(localDate({ a: [2026, 10, 20] }, 'a')).toBe('2026-10-20');
    expect(localDate({ a: [2026, 1, 5] }, 'a')).toBe('2026-01-05');
    expect(localDate({ a: '' }, 'a')).toBeNull();
    for (const bad of ['2026-02-30', '2026-1-1', [2026, 2, 30], [2026, 10], [2026.5, 10, 20], 20261020, {}]) {
      expect(unreadable(() => localDate({ a: bad }, 'a')), JSON.stringify(bad)).not.toBeNull();
    }
  });

  it('instant: ISO 문자열 또는 epoch 초 숫자, 오프셋 없는 시각은 오류', () => {
    expect(instant({ a: '2026-10-20T01:00:00Z' }, 'a')!.toString()).toBe('2026-10-20T01:00:00Z');
    expect(instant({ a: 1_760_000_000 }, 'a')!.toString()).toBe('2025-10-09T08:53:20Z');
    expect(instant({ a: '' }, 'a')).toBeNull();
    for (const bad of ['2026-10-20T01:00:00', 'now', true, [], {}]) {
      expect(unreadable(() => instant({ a: bad }, 'a')), JSON.stringify(bad)).not.toBeNull();
    }
  });

  it('list/listItem: 배열이 아니면 오류, 원소가 객체가 아니면 오류', () => {
    expect(list({ a: [1] }, 'a')).toEqual([1]);
    expect(list({}, 'a')).toBeNull();
    expect(unreadable(() => list({ a: {} }, 'a'))).not.toBeNull();
    expect(listItem({ x: 1 }, 'a')).toEqual({ x: 1 });
    for (const bad of [null, 1, 'x', [1]]) expect(unreadable(() => listItem(bad, 'a'))).not.toBeNull();
  });
});

describe('제약 검증 문구(Bean Validation)', () => {
  it('첫 위반만 "필드: 문구"로 돌려준다(선언 순서)', () => {
    const run = () => validate(notBlank('a', 'ok'), notBlank('b', ' '), notNull('c', null));
    try {
      run();
      expect.unreachable();
    } catch (error) {
      expect(resolve('ko', (error as ApiError).localized)).toBe('b: 비어 있을 수 없습니다.');
      expect((error as ApiError).status).toBe(400);
    }
    expect(() => validate()).not.toThrow();
  });

  it('notBlank/notNull/notEmpty/positiveOrZero 의 통과·위반 경계', () => {
    expect(violation(notBlank('f', 'x'))).toBeNull();
    expect(violation(notBlank('f', null))).toMatch(/^f: /);
    expect(violation(notBlank('f', '　 \t'))).toMatch(/^f: /); // 전각 공백도 공백
    expect(violation(notNull('f', 0))).toBeNull();
    expect(violation(notNull('f', undefined))).toMatch(/^f: /);
    expect(violation(notEmpty('f', [1]))).toBeNull();
    expect(violation(notEmpty('f', []))).toMatch(/^f: /);
    expect(violation(notEmpty('f', null))).toMatch(/^f: /);
    expect(violation(positiveOrZero('f', 0))).toBeNull();
    expect(violation(positiveOrZero('f', null))).toBeNull();
    expect(violation(positiveOrZero('f', -0.01))).toMatch(/^f: /);
  });

  it('size: 문자열 길이는 UTF-16 코드 단위(Java String.length), min/max 를 문구에 치환, null 은 통과', () => {
    expect(violation(size('f', null, 8, 72, 'validation.password.size'))).toBeNull();
    expect(violation(size('f', 'a'.repeat(8), 8, 72, 'validation.password.size'))).toBeNull();
    expect(violation(size('f', 'a'.repeat(72), 8, 72, 'validation.password.size'))).toBeNull();
    expect(violation(size('f', 'a'.repeat(7), 8, 72, 'validation.password.size'))).toBe('f: 비밀번호는 8자 이상이어야 합니다.');
    expect(violation(size('f', 'a'.repeat(73), 8, 72, 'validation.password.size'), 'en')).toMatch(/^f: /);
    // 이모지 하나는 UTF-16 으로 2 단위
    expect(violation(size('f', '😀'.repeat(36), 8, 72, 'validation.password.size'))).toBeNull();
    expect(violation(size('f', '😀'.repeat(37), 8, 72, 'validation.password.size'))).not.toBeNull();
    expect(violation(size('f', [1, 2, 3], 0, 2, 'validation.activities.size'))).toMatch(/^f: /);
  });

  it('maxUtf8Bytes: BCrypt 72바이트 한계(한글은 3바이트)', () => {
    expect(violation(maxUtf8Bytes('f', '가'.repeat(24), 72, 'validation.password.bytes'))).toBeNull();
    expect(violation(maxUtf8Bytes('f', '가'.repeat(25), 72, 'validation.password.bytes'))).toMatch(/^f: .*72/);
    expect(violation(maxUtf8Bytes('f', null, 72, 'validation.password.bytes'))).toBeNull();
  });

  it('pattern: null 은 통과, 일치해야 통과(전체 일치는 정규식이 책임)', () => {
    const re = /^[a-z]{4,8}$/;
    expect(violation(pattern('f', null, re, 'validation.username.pattern'))).toBeNull();
    expect(violation(pattern('f', 'abcd', re, 'validation.username.pattern'))).toBeNull();
    expect(violation(pattern('f', 'abc', re, 'validation.username.pattern'))).toMatch(/^f: /);
    expect(violation(pattern('f', 'abcd\n', re, 'validation.username.pattern'))).toMatch(/^f: /); // 끝의 개행도 거부(Java matches() 와 같음)
  });
});

describe('설정 검증(loadConfig)', () => {
  const env = (extra: Record<string, unknown> = {}) => ({ AUTH_JWT_SECRET: TEST_SECRET, ...extra });
  const fail = (extra: Record<string, unknown>) => {
    try {
      loadConfig(env(extra));
    } catch (error) {
      return (error as Error).message;
    }
    return null;
  };

  it('기본값은 Spring application.yml 과 같다', () => {
    const c = loadConfig(env());
    expect(c).toMatchObject({
      timezone: 'Asia/Seoul',
      dbPoolMax: 5,
      apiDocsEnabled: false,
      corsAllowedOrigins: ['http://localhost:5173'],
      httpTimeoutMs: 8000,
      jwt: { ttlSeconds: 3600, watchTtlSeconds: 7 * 86400, issuer: 'wellness-game', audience: 'wellness-game-api' },
      refresh: { ttlMs: 30 * 86_400_000, absoluteTtlMs: 90 * 86_400_000, readTtlMs: 7 * 86_400_000, readAbsoluteTtlMs: 30 * 86_400_000, reuseGraceMs: 30_000 },
      rateLimit: { syncPerMinute: 30, authPerMinute: 30, loginPerMinute: 10, refreshPerMinute: 120, generalPerMinute: 0, trustForwardedHeader: false, trustedProxyHops: 0 },
      lockout: { maxFailedLogins: 5, maxFailedLoginsPerUsername: 50, lockoutMinutes: 15 },
    });
    expect(c.jwt.legacyCutoffEpochSeconds).toBe(Date.parse('2026-10-06T00:00:00Z') / 1000);
    expect(c.oauth.apple.requireNonce).toBe(false);
    expect(c.oauth.googleNative.jwksCacheTtlMs).toBe(3_600_000);
    expect(c.oauth.googleNative.jwksStaleMaxMs).toBe(86_400_000);
  });

  it('시크릿: 없음·짧음(바이트 기준)·단순·예시 문구는 기동 실패, 값 자체는 메시지에 넣지 않음', () => {
    expect(() => loadConfig({})).toThrow(/32바이트.*0바이트/);
    expect(fail({ AUTH_JWT_SECRET: 'a'.repeat(31) })).toMatch(/31바이트/);
    expect(fail({ AUTH_JWT_SECRET: '가'.repeat(11) })).toMatch(/33바이트|단순/); // 33바이트지만 문자 종류 1
    expect(fail({ AUTH_JWT_SECRET: 'ab'.repeat(20) })).toMatch(/단순/);
    const placeholder = fail({ AUTH_JWT_SECRET: 'Change-ME-' + 'abcdefghijklmnopqrstuvwxyz0123456789' });
    expect(placeholder).toMatch(/예시 값/);
    expect(placeholder).not.toContain('abcdefghijklmnop');
    expect(fail({ AUTH_JWT_SECRET: 'local-dev-only-' + 'abcdefghijklmnopqrstuvwxyz' })).toMatch(/예시 값/);
  });

  it('시크릿 경계: 정확히 32바이트·서로 다른 문자 8종이면 통과, 7종이면 실패', () => {
    expect(fail({ AUTH_JWT_SECRET: 'abcdefgh'.repeat(4) })).toBeNull();
    expect(fail({ AUTH_JWT_SECRET: 'abcdefg'.repeat(5) })).toMatch(/단순/);
  });

  it('숫자·불리언·시간대 형식 오류에 설정 이름을 담은 메시지', () => {
    expect(fail({ DB_POOL_MAX: 'five' })).toMatch(/DB_POOL_MAX/);
    expect(fail({ APP_RATE_LIMIT_SYNC_PER_MINUTE: '1.5' })).toMatch(/APP_RATE_LIMIT_SYNC_PER_MINUTE/);
    expect(fail({ APP_API_DOCS_ENABLED: 'yes' })).toMatch(/APP_API_DOCS_ENABLED/);
    expect(fail({ APP_TIMEZONE: 'Mars/Base' })).toMatch(/APP_TIMEZONE/);
    expect(fail({ AUTH_JWT_LEGACY_CUTOFF: 'someday' })).toMatch(/AUTH_JWT_LEGACY_CUTOFF/);
    expect(fail({ AUTH_JWT_TTL: 'soon' })).toMatch(/기간 형식/);
  });

  it('빈 값은 기본값, true/false 는 대소문자·공백 무시, 숫자는 앞뒤 공백 허용', () => {
    const c = loadConfig(env({ DB_POOL_MAX: '  ', APP_API_DOCS_ENABLED: ' TRUE ', APP_RATE_LIMIT_SYNC_PER_MINUTE: ' 7 ' }));
    expect(c.dbPoolMax).toBe(5);
    expect(c.apiDocsEnabled).toBe(true);
    expect(c.rateLimit.syncPerMinute).toBe(7);
    expect(loadConfig(env({ AUTH_JWT_LEGACY_CUTOFF: '' })).jwt.legacyCutoffEpochSeconds).toBeNull();
  });

  it('TTL 규칙: 1초 미만 거부, 절대 TTL 은 유휴 TTL 이상, 유예·보관은 음수 불가', () => {
    expect(fail({ AUTH_JWT_TTL: '999ms' })).toMatch(/auth\.jwt\.ttl/);
    expect(fail({ AUTH_WATCH_TOKEN_TTL: '0s' })).toMatch(/watch-ttl/);
    expect(fail({ AUTH_REFRESH_TTL: '500ms' })).toMatch(/auth\.refresh\.ttl/);
    expect(fail({ AUTH_REFRESH_TTL: '10d', AUTH_REFRESH_ABSOLUTE_TTL: '9d' })).toMatch(/absolute-ttl/);
    expect(fail({ AUTH_REFRESH_READ_TTL: '10d', AUTH_REFRESH_READ_ABSOLUTE_TTL: '9d' })).toMatch(/absolute-ttl/);
    expect(fail({ AUTH_REFRESH_REUSE_GRACE: '-1s' })).toMatch(/0 이상/);
    expect(fail({ AUTH_REFRESH_REVOKED_RETENTION: '-1s' })).toMatch(/0 이상/);
    expect(fail({ AUTH_REFRESH_TTL: '10d', AUTH_REFRESH_ABSOLUTE_TTL: '10d' })).toBeNull();
    expect(fail({ AUTH_REFRESH_REUSE_GRACE: '0s' })).toBeNull();
  });

  it('레이트 리밋·잠금 규칙', () => {
    expect(fail({ APP_RATE_LIMIT_MAX_KEYS: '0' })).toMatch(/max-keys/);
    expect(fail({ APP_RATE_LIMIT_TRUSTED_PROXY_HOPS: '-1' })).toMatch(/HOPS/);
    expect(fail({ APP_AUTH_LOCKOUT_MINUTES: '0' })).toMatch(/lockout-minutes/);
    // 잠금을 끄면(둘 다 0) 잠금 시간은 검사하지 않는다
    expect(fail({ APP_AUTH_MAX_FAILED_LOGINS: '0', APP_AUTH_MAX_FAILED_LOGINS_PER_USERNAME: '0', APP_AUTH_LOCKOUT_MINUTES: '0' })).toBeNull();
    // 한쪽만 켜져 있어도 검사한다
    expect(fail({ APP_AUTH_MAX_FAILED_LOGINS: '0', APP_AUTH_LOCKOUT_MINUTES: '0' })).toMatch(/lockout-minutes/);
  });

  it('JWKS TTL: 0 이하이면 기본 1시간, stale-max 는 음수면 기본 24시간(0 은 허용)', () => {
    const c = loadConfig(
      env({ OAUTH_GOOGLE_JWKS_CACHE_TTL: '0s', OAUTH_GOOGLE_JWKS_STALE_MAX: '-1s', OAUTH_APPLE_JWKS_CACHE_TTL: '5m', OAUTH_APPLE_JWKS_STALE_MAX: '0s' }),
    );
    expect(c.oauth.googleNative).toMatchObject({ jwksCacheTtlMs: 3_600_000, jwksStaleMaxMs: 86_400_000 });
    expect(c.oauth.apple).toMatchObject({ jwksCacheTtlMs: 300_000, jwksStaleMaxMs: 0 });
  });

  it('네이티브 구글 클라이언트 ID: 쉼표 목록(공백·빈 항목 무시), 구 이름 OAUTH_GOOGLE_IOS_CLIENT_ID 도 인식, 웹 ID 와 겹치면 실패', () => {
    expect(loadConfig(env({ OAUTH_GOOGLE_NATIVE_CLIENT_IDS: ' a , ,b ' })).oauth.googleNative.clientIds).toEqual(['a', 'b']);
    expect(loadConfig(env({ OAUTH_GOOGLE_IOS_CLIENT_ID: 'ios' })).oauth.googleNative.clientIds).toEqual(['ios']);
    expect(loadConfig(env({ OAUTH_GOOGLE_NATIVE_CLIENT_IDS: 'n', OAUTH_GOOGLE_IOS_CLIENT_ID: 'ios' })).oauth.googleNative.clientIds).toEqual(['n']);
    expect(fail({ OAUTH_GOOGLE_CLIENT_ID: ' web ', OAUTH_GOOGLE_NATIVE_CLIENT_IDS: 'x,web' })).toMatch(/웹 로그인 클라이언트 ID/);
    expect(fail({ OAUTH_GOOGLE_CLIENT_ID: '', OAUTH_GOOGLE_NATIVE_CLIENT_IDS: '' })).toBeNull();
  });

  it('문자열이 아닌 환경 값은 없는 것으로 본다', () => {
    expect(loadConfig(env({ DB_POOL_MAX: 99, APP_API_DOCS_ENABLED: true })).dbPoolMax).toBe(5);
  });

  it('DB URL: Spring jdbc 형식과 일반 형식, 자격 증명 보강·인코딩', () => {
    expect(resolveDatabaseUrl(undefined, undefined, undefined)).toBe('postgresql://wellness:wellness@localhost:5432/wellness_game');
    expect(resolveDatabaseUrl('jdbc:postgresql://db:5432/app?sslmode=require', 'u', 'p w')).toBe(
      'postgresql://u:p%20w@db:5432/app?sslmode=require',
    );
    expect(resolveDatabaseUrl('postgres://x:y@h/db', 'ignored', 'ignored')).toBe('postgres://x:y@h/db');
    expect(resolveDatabaseUrl('  ', 'u', 'p')).toContain('localhost:5432/wellness_game');
    expect(resolveDatabaseUrl('postgres://onlyuser@h/db', undefined, 'pw').startsWith('postgres://onlyuser@h')).toBe(true);
  });
});
