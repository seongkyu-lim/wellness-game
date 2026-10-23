import { createHmac, createHash, randomBytes } from 'node:crypto';
import bcrypt from 'bcryptjs';
import { describe, expect, it } from 'vitest';
import { JwtTokenService } from '../../src/auth/jwt.js';
import { sha256Hex, randomBase64Url } from '../../src/lib/crypto.js';
import { createServices } from '../../src/services.js';
import { MutableClock, T0, TEST_SECRET, testConfig } from '../support/fixtures.js';
import { SPRING_BCRYPT, SPRING_FULL_TOKEN, SPRING_LEGACY_TOKEN, SPRING_READ_TOKEN, SPRING_SHA256 } from '../support/spring-fixtures.js';

/**
 * Spring(JwtTokenService·RefreshTokenService·BCryptPasswordEncoder) 와 Hono 가 서로의 값을 받아들이는지.
 * 고정 값(test/support/spring-fixtures.ts)은 Spring 쪽 라이브러리(jjwt 0.12.3, spring-security-crypto 6.3.0)로 실제 생성했다.
 */
const b64urlJson = (part: string) => JSON.parse(Buffer.from(part, 'base64url').toString('utf8'));
const decode = (token: string) => {
  const [h, p, s] = token.split('.') as [string, string, string];
  return { headerRaw: Buffer.from(h, 'base64url').toString('utf8'), header: b64urlJson(h), payload: b64urlJson(p), signature: s, signingInput: `${h}.${p}` };
};

function jwtService(clock = new MutableClock(T0), env: Record<string, string> = {}) {
  const config = testConfig(env);
  return { jwt: new JwtTokenService(config.jwt, clock.fn), clock, config };
}

describe('JWT: Spring 이 발급한 토큰을 Hono 가 받는다', () => {
  it('전체 권한 토큰: sub·scope=full·iat', async () => {
    const { jwt } = jwtService();
    await expect(jwt.authenticate(SPRING_FULL_TOKEN)).resolves.toEqual({ userId: 'kakao:12345', scope: 'full', issuedAt: T0 / 1000 });
  });

  it('조회 전용(scope=read) 토큰', async () => {
    const { jwt } = jwtService();
    await expect(jwt.authenticate(SPRING_READ_TOKEN)).resolves.toEqual({ userId: 'password:abc-123', scope: 'read', issuedAt: T0 / 1000 });
  });

  it('iss·aud 없는 #55 이전 토큰: iat(2026-09-20)가 legacy cutoff(2026-10-06) 이전이라 통과, cutoff 가 더 이르거나 꺼져 있으면 거부', async () => {
    const { jwt } = jwtService();
    await expect(jwt.authenticate(SPRING_LEGACY_TOKEN)).resolves.toMatchObject({ userId: 'google:legacy', scope: 'full' });
    // cutoff 를 더 이르게 설정하면 같은 토큰이 거부된다
    const strict = jwtService(new MutableClock(T0), { AUTH_JWT_LEGACY_CUTOFF: '2026-09-01T00:00:00Z' }).jwt;
    await expect(strict.authenticate(SPRING_LEGACY_TOKEN)).rejects.toMatchObject({ status: 401 });
    const none = jwtService(new MutableClock(T0), { AUTH_JWT_LEGACY_CUTOFF: '' }).jwt;
    await expect(none.authenticate(SPRING_LEGACY_TOKEN)).rejects.toMatchObject({ status: 401 });
  });

  it('만료 경계: Spring 토큰의 exp(iat+3600) 직전엔 통과하고 정각에 만료', async () => {
    const clock = new MutableClock(T0 + 3_599_000);
    const { jwt } = jwtService(clock);
    await expect(jwt.authenticate(SPRING_FULL_TOKEN)).resolves.toBeTruthy();
    clock.now = T0 + 3_600_000;
    await expect(jwt.authenticate(SPRING_FULL_TOKEN)).rejects.toMatchObject({ localized: { code: 'auth.token-expired' } });
  });

  it('다른 시크릿으로 서명된 Spring 토큰은 거부', async () => {
    const other = jwtService(new MutableClock(T0), { AUTH_JWT_SECRET: 'another-secret-value-0123456789-ABCDEFGHIJKLMNOP' }).jwt;
    await expect(other.authenticate(SPRING_FULL_TOKEN)).rejects.toMatchObject({ localized: { code: 'auth.token-invalid' } });
  });

  it('iss/aud 설정이 다르면 거부(AUTH_JWT_ISSUER·AUDIENCE 를 두 서버가 같게 맞춰야 한다)', async () => {
    const other = jwtService(new MutableClock(T0), { AUTH_JWT_ISSUER: 'other-issuer' }).jwt;
    await expect(other.authenticate(SPRING_FULL_TOKEN)).rejects.toMatchObject({ status: 401 });
    const otherAud = jwtService(new MutableClock(T0), { AUTH_JWT_AUDIENCE: 'other-aud' }).jwt;
    await expect(otherAud.authenticate(SPRING_FULL_TOKEN)).rejects.toMatchObject({ status: 401 });
  });
});

describe('JWT: Hono 가 발급한 토큰의 모양이 Spring 과 같다', () => {
  it('같은 입력이면 Spring 토큰과 바이트까지 같다(헤더·클레임 순서·서명)', async () => {
    const { jwt } = jwtService();
    const issued = await jwt.issue('kakao:12345');
    expect(issued).toEqual({ accessToken: SPRING_FULL_TOKEN, tokenType: 'Bearer', expiresIn: 3600 });
  });

  it('조회 전용 토큰: 클레임 집합·값이 Spring 과 같고(순서만 다를 수 있음) 서명이 유효', async () => {
    const { jwt } = jwtService();
    const issued = await jwt.issueWatch('password:abc-123');
    const mine = decode(issued.accessToken);
    const spring = decode(SPRING_READ_TOKEN);
    expect(mine.payload).toEqual(spring.payload);
    expect(mine.headerRaw).toBe(spring.headerRaw);
    expect(issued.expiresIn).toBe(7 * 86400);
    const expected = createHmac('sha256', TEST_SECRET).update(mine.signingInput).digest('base64url');
    expect(mine.signature).toBe(expected);
  });

  it('독립 구현(node:crypto HMAC)으로 직접 검증: 헤더는 {"alg":"HS256"} 뿐, aud 는 문자열 1개의 배열, 패딩 없음', async () => {
    const { jwt } = jwtService();
    for (const issued of [await jwt.issue('u:1'), await jwt.issueForScope('u:2', 'read'), await jwt.issueWatch('u:3')]) {
      const t = decode(issued.accessToken);
      expect(t.headerRaw).toBe('{"alg":"HS256"}');
      expect(Object.keys(t.payload).sort()).toEqual(expect.arrayContaining(['aud', 'exp', 'iat', 'iss', 'sub']));
      expect(Object.keys(t.payload).filter((k) => !['aud', 'exp', 'iat', 'iss', 'sub', 'scope'].includes(k))).toEqual([]);
      expect(t.payload.aud).toEqual(['wellness-game-api']);
      expect(t.payload.iss).toBe('wellness-game');
      expect(Number.isInteger(t.payload.iat) && Number.isInteger(t.payload.exp)).toBe(true);
      expect(issued.accessToken).not.toMatch(/[=+/]/);
      expect(t.signature).toBe(createHmac('sha256', Buffer.from(TEST_SECRET, 'utf8')).update(t.signingInput).digest('base64url'));
    }
  });

  it('scope 클레임은 read 일 때만 있고 값은 "read"', async () => {
    const { jwt } = jwtService();
    expect(decode((await jwt.issue('u')).accessToken).payload).not.toHaveProperty('scope');
    expect(decode((await jwt.issueForScope('u', 'full')).accessToken).payload).not.toHaveProperty('scope');
    expect(decode((await jwt.issueForScope('u', 'read')).accessToken).payload.scope).toBe('read');
  });

  it('유효 기간: 일반 3600초, 웹(read family) 도 3600초, Watch 7일(Spring issue/issueReadOnly 와 같음)', async () => {
    const { jwt } = jwtService();
    const span = async (p: Promise<{ accessToken: string }>) => {
      const { payload } = decode((await p).accessToken);
      return payload.exp - payload.iat;
    };
    expect(await span(jwt.issue('u'))).toBe(3600);
    expect(await span(jwt.issueForScope('u', 'read'))).toBe(3600);
    expect(await span(jwt.issueWatch('u'))).toBe(7 * 86400);
  });

  it('두 서버를 오가는 토큰: Hono 발급 → (Spring 과 동일한 규칙의) Hono 검증, 시크릿이 UTF-8 바이트 그대로 키로 쓰인다', async () => {
    const korean = '한글시크릿키-0123456789-abcdefghij'; // 바이트 수가 길이보다 큼
    const a = jwtService(new MutableClock(T0), { AUTH_JWT_SECRET: korean }).jwt;
    const token = (await a.issue('u:1')).accessToken;
    const { signingInput, signature } = decode(token);
    expect(signature).toBe(createHmac('sha256', Buffer.from(korean, 'utf8')).update(signingInput).digest('base64url'));
    await expect(a.authenticate(token)).resolves.toMatchObject({ userId: 'u:1' });
  });
});

describe('리프레시 토큰 해시: Spring 의 Sha256.hex 와 같다', () => {
  it.each(Object.entries(SPRING_SHA256))('SHA-256 hex(%s)', async (input, expected) => {
    expect(await sha256Hex(input)).toBe(expected);
  });

  it('무작위 토큰 200개에서 node:crypto 와 항상 일치(소문자 64자 hex)', async () => {
    for (let i = 0; i < 200; i++) {
      const raw = i % 2 === 0 ? randomBase64Url(32) : randomBytes(i % 40).toString('latin1');
      const expected = createHash('sha256').update(raw, 'utf8').digest('hex');
      expect(await sha256Hex(raw)).toBe(expected);
      expect(expected).toMatch(/^[0-9a-f]{64}$/);
    }
  });

  it('원문 토큰 형식은 Spring 과 같은 43자 base64url(32바이트)', () => {
    expect(randomBase64Url(32)).toMatch(/^[A-Za-z0-9_-]{43}$/);
  });
});

describe('BCrypt: Spring($2a$10$) 해시 호환', () => {
  it.each(Object.entries(SPRING_BCRYPT))('Spring 이 만든 해시를 검증한다(%s…)', async (password, hash) => {
    expect(hash).toMatch(/^\$2a\$10\$[./A-Za-z0-9]{53}$/);
    expect(await bcrypt.compare(password, hash)).toBe(true);
    expect(await bcrypt.compare('X' + password.slice(1), hash)).toBe(false);
  });

  it('Hono 가 만든 해시는 $2a/$2b 접두사의 cost 10 이고, Spring 형식($2a)으로 바꿔도 검증된다', async () => {
    const hash = await bcrypt.hash('password123', 10);
    expect(hash).toMatch(/^\$2[ab]\$10\$/);
    expect(await bcrypt.compare('password123', hash.replace(/^\$2b\$/, '$2a$'))).toBe(true);
    // Spring 해시를 $2b 로 읽어도 같다(두 접두사는 알고리즘이 같다)
    expect(await bcrypt.compare('password123', SPRING_BCRYPT.password123.replace(/^\$2a\$/, '$2b$'))).toBe(true);
  });

  it('72바이트를 넘는 부분은 해시에 반영되지 않는다(요청 검증이 72바이트 상한을 두는 이유)', async () => {
    const hash = SPRING_BCRYPT['a'.repeat(72)]!;
    expect(await bcrypt.compare('a'.repeat(72) + 'tail', hash)).toBe(true);
  });
});

describe('서비스 묶음의 Spring 호환 설정', () => {
  it('createServices 의 JWT 서비스는 Spring 토큰을 받는다(통합 경로 확인)', async () => {
    const services = createServices(testConfig(), { clock: () => T0 });
    await expect(services.jwt.authenticate(SPRING_FULL_TOKEN)).resolves.toMatchObject({ userId: 'kakao:12345' });
  });
});
