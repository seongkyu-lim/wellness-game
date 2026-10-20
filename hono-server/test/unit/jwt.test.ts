import { SignJWT, decodeJwt, decodeProtectedHeader } from 'jose';
import { describe, expect, it } from 'vitest';
import { JwtTokenService } from '../../src/auth/jwt.js';
import { ApiError } from '../../src/lib/errors.js';
import { MutableClock, T0, TEST_SECRET, testConfig } from '../support/fixtures.js';

const key = new TextEncoder().encode(TEST_SECRET);

async function errorCode(promise: Promise<unknown>): Promise<string> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof ApiError) return `${error.status} ${error.localized.code}`;
    throw error;
  }
  throw new Error('예외가 나지 않았습니다.');
}

describe('JwtTokenService', () => {
  const config = testConfig();

  it('Spring(jjwt)과 같은 형식으로 발급한다: HS256, sub·iss·aud(배열)·iat·exp, scope 없음', async () => {
    const clock = new MutableClock();
    const service = new JwtTokenService(config.jwt, clock.fn);
    const issued = await service.issue('google:123');
    expect(issued).toMatchObject({ tokenType: 'Bearer', expiresIn: 3600 });
    expect(decodeProtectedHeader(issued.accessToken)).toEqual({ alg: 'HS256' });
    const claims = decodeJwt(issued.accessToken);
    expect(claims).toEqual({
      sub: 'google:123',
      iss: 'wellness-game',
      aud: ['wellness-game-api'],
      iat: T0 / 1000,
      exp: T0 / 1000 + 3600,
    });
    expect(await service.authenticate(issued.accessToken)).toEqual({ userId: 'google:123', scope: 'full', issuedAt: T0 / 1000 });
  });

  it('Watch 토큰은 scope=read 와 watch-ttl(7일)', async () => {
    const service = new JwtTokenService(config.jwt, () => T0);
    const issued = await service.issueWatch('kakao:1');
    expect(issued.expiresIn).toBe(7 * 86400);
    expect(decodeJwt(issued.accessToken).scope).toBe('read');
    expect((await service.authenticate(issued.accessToken)).scope).toBe('read');
  });

  it('웹(read family) 액세스 토큰은 scope=read 에 일반 ttl', async () => {
    const service = new JwtTokenService(config.jwt, () => T0);
    const issued = await service.issueForScope('google:1', 'read');
    expect(issued.expiresIn).toBe(3600);
    expect((await service.authenticate(issued.accessToken)).scope).toBe('read');
  });

  it('aud 가 문자열인 토큰(다른 구현)도 받는다', async () => {
    const token = await new SignJWT({})
      .setProtectedHeader({ alg: 'HS256' })
      .setSubject('apple:x')
      .setIssuer('wellness-game')
      .setAudience('wellness-game-api')
      .setIssuedAt(T0 / 1000)
      .setExpirationTime(T0 / 1000 + 60)
      .sign(key);
    const service = new JwtTokenService(config.jwt, () => T0);
    expect((await service.authenticate(token)).userId).toBe('apple:x');
  });

  it('만료·서명 불일치·iss 불일치·모르는 scope·빈 토큰', async () => {
    const clock = new MutableClock();
    const service = new JwtTokenService(config.jwt, clock.fn);
    const issued = await service.issue('u');
    clock.advance(3600_000);
    expect(await errorCode(service.authenticate(issued.accessToken))).toBe('401 auth.token-expired');

    const other = new JwtTokenService({ ...config.jwt, secret: 'another-secret-value-0123456789-ABCDEFGHIJ' }, () => T0);
    expect(await errorCode(service.authenticate((await other.issue('u')).accessToken))).toBe('401 auth.token-invalid');

    const wrongIssuer = new JwtTokenService({ ...config.jwt, issuer: 'someone-else' }, () => T0);
    const now = new JwtTokenService(config.jwt, () => T0);
    expect(await errorCode(now.authenticate((await wrongIssuer.issue('u')).accessToken))).toBe('401 auth.token-invalid');

    const badScope = await new SignJWT({ scope: 'admin' })
      .setProtectedHeader({ alg: 'HS256' })
      .setSubject('u')
      .setIssuer('wellness-game')
      .setAudience(['wellness-game-api'])
      .setIssuedAt(T0 / 1000)
      .setExpirationTime(T0 / 1000 + 60)
      .sign(key);
    expect(await errorCode(now.authenticate(badScope))).toBe('401 auth.token-invalid');
    expect(await errorCode(now.authenticate(''))).toBe('401 auth.login-required');
    expect(await errorCode(now.authenticate('not-a-jwt'))).toBe('401 auth.token-invalid');
  });

  it('iss·aud 없는 이전 형식 토큰은 iat 가 legacy cutoff 이전일 때만 통과', async () => {
    const legacy = (iat: number) =>
      new SignJWT({}).setProtectedHeader({ alg: 'HS256' }).setSubject('u').setIssuedAt(iat).setExpirationTime(iat + 60 * 86400).sign(key);
    const cutoff = Date.parse('2026-10-06T00:00:00Z') / 1000;
    const service = new JwtTokenService(config.jwt, () => T0);
    expect((await service.authenticate(await legacy(cutoff - 86400))).userId).toBe('u');
    expect(await errorCode(service.authenticate(await legacy(cutoff + 1)))).toBe('401 auth.token-invalid');
    const noLegacy = new JwtTokenService({ ...config.jwt, legacyCutoffEpochSeconds: null }, () => T0);
    expect(await errorCode(noLegacy.authenticate(await legacy(cutoff - 86400)))).toBe('401 auth.token-invalid');
  });

  it('HS256 이외 알고리즘(none 등)은 거부', async () => {
    const service = new JwtTokenService(config.jwt, () => T0);
    const header = btoa(JSON.stringify({ alg: 'none' })).replace(/=+$/, '');
    const payload = btoa(JSON.stringify({ sub: 'u', iss: 'wellness-game', aud: ['wellness-game-api'], exp: T0 / 1000 + 60 })).replace(/=+$/, '');
    expect(await errorCode(service.authenticate(`${header}.${payload}.`))).toBe('401 auth.token-invalid');
  });
});

describe('설정 검증', () => {
  it('짧거나 단순한 시크릿은 기동 실패(Spring 과 같음)', () => {
    expect(() => testConfig({ AUTH_JWT_SECRET: 'short' })).toThrow(/32바이트/);
    expect(() => testConfig({ AUTH_JWT_SECRET: 'a'.repeat(40) })).toThrow(/너무 단순/);
  });
});
