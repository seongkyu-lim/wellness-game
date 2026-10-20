import { describe, expect, it } from 'vitest';
import type { Db } from '../../src/db/client.js';
import { buildTestApp } from '../support/api.js';
import { KO } from '../../src/lib/messages-data.js';

/** DB 없이 확인하는 HTTP 계약(보안 헤더·CORS·인증 오류·검증 문구·레이트 리밋·404/405·문서) */
const noDb = new Proxy({}, {
  get() {
    throw new Error('DB 를 쓰면 안 되는 테스트입니다.');
  },
}) as unknown as Db;

function app(env?: Record<string, string>) {
  const built = buildTestApp({ db: noDb, env });
  // 토큰 무효화 기준 조회(DB)를 건너뛴다
  built.services.validity.isRevoked = async () => false;
  return built;
}

describe('HTTP 계약(DB 없음)', () => {
  it('GET /healthz: UP, no-store, 보안 헤더', async () => {
    const { call } = app();
    const res = await call('GET', '/healthz');
    expect(res.status).toBe(200);
    expect(res.json).toEqual({ status: 'UP' });
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(res.headers.get('strict-transport-security')).toBe('max-age=31536000; includeSubDomains');
    expect(res.headers.get('x-content-type-options')).toBe('nosniff');
    expect(res.headers.get('x-frame-options')).toBe('DENY');
    expect(res.headers.get('content-security-policy')).toBe("frame-ancestors 'none'");
    expect(res.headers.get('referrer-policy')).toBe('no-referrer');
  });

  it('CORS: 허용 출처만, 오류 응답에도 헤더', async () => {
    const { call } = app();
    const preflight = await call('OPTIONS', '/api/characters/me', {
      headers: { Origin: 'https://app.example.com', 'Access-Control-Request-Method': 'GET' },
    });
    expect(preflight.headers.get('access-control-allow-origin')).toBe('https://app.example.com');
    const denied = await call('GET', '/api/characters/me', { headers: { Origin: 'https://evil.example' } });
    expect(denied.headers.get('access-control-allow-origin')).toBeNull();
    const unauthorized = await call('GET', '/api/characters/me', { headers: { Origin: 'http://localhost:5173' } });
    expect(unauthorized.status).toBe(401);
    expect(unauthorized.headers.get('access-control-allow-origin')).toBe('http://localhost:5173');
    expect(unauthorized.headers.get('access-control-expose-headers')).toContain('Retry-After');
  });

  it('401: 토큰 없음·Bearer 아님·잘못된 토큰 + WWW-Authenticate, 오류 본문 형식', async () => {
    const { call } = app();
    const none = await call('GET', '/api/characters/me');
    expect(none.status).toBe(401);
    expect(none.headers.get('www-authenticate')).toBe('Bearer');
    expect(Object.keys(none.json).sort()).toEqual(['error', 'message', 'status', 'timestamp']);
    expect(none.json).toMatchObject({ status: 401, error: 'Unauthorized', message: '인증이 필요합니다. 로그인해 주세요.' });
    const basic = await call('GET', '/api/characters/me', { headers: { Authorization: 'Basic abc' } });
    expect(basic.json.message).toBe('토큰이 유효하지 않습니다.');
    const en = await call('GET', '/api/characters/me', { token: 'garbage', headers: { 'Accept-Language': 'en-US,en;q=0.9' } });
    expect(en.json.message).toBe('Your session is invalid. Please sign in again.');
    // 지원하지 않는 웹 공급자 경로는 공개 목록에 없어 인증부터 요구한다(Spring 과 같음)
    expect((await call('POST', '/api/auth/github', { body: {} })).status).toBe(401);
  });

  it('조회 전용 토큰은 허용 목록의 GET 만(나머지 403)', async () => {
    const { call, services } = app();
    const read = (await services.jwt.issueWatch('kakao:1')).accessToken;
    const sync = await call('POST', '/api/health-activities/sync', { token: read, body: {} });
    expect(sync.status).toBe(403);
    expect(sync.json.message).toBe('조회 전용 토큰으로는 이 요청을 할 수 없습니다.');
    expect((await call('POST', '/api/auth/watch-token', { token: read })).status).toBe(403);
    expect((await call('GET', '/api/characters/kakao:2', { token: read })).json.message).toBe('다른 사용자의 데이터입니다.');
  });

  it('검증 문구: 선언 순서의 첫 위반을 "필드: 문구"로(ko/en), 본문 형식 오류는 일반 문구', async () => {
    const { call } = app();
    expect((await call('POST', '/api/auth/signup', { body: { username: 'ab', password: 'x' } })).json.message).toBe(
      'username: 아이디는 영문·숫자·밑줄 4~32자여야 합니다.',
    );
    expect(
      (await call('POST', '/api/auth/signup', { body: { username: 'abcd', password: '가'.repeat(25) } })).json.message,
    ).toBe('password: 비밀번호는 UTF-8 기준 72바이트 이하여야 합니다(한글은 글자당 3바이트).');
    const en = await call('POST', '/api/auth/login', { body: { username: ' ' }, headers: { 'Accept-Language': 'en' } });
    expect(en.json.message).toBe('username: can’t be blank.');
    expect((await call('POST', '/api/auth/refresh', { body: { refreshToken: 'x'.repeat(257) } })).json.message).toBe(
      'refreshToken: 토큰은 256자 이하여야 합니다.',
    );
    expect((await call('POST', '/api/auth/login', { body: '{bad json' })).json.message).toBe(
      '요청 본문을 읽을 수 없습니다. 형식을 확인해 주세요.',
    );
    expect((await call('POST', '/api/auth/login', { body: { username: { a: 1 }, password: 'x' } })).json.message).toBe(
      '요청 본문을 읽을 수 없습니다. 형식을 확인해 주세요.',
    );
    expect((await call('POST', '/api/auth/login', { body: 'x', headers: { 'Content-Type': 'text/plain' } })).status).toBe(415);
  });

  it('웹 코드 교환: 구글·카카오는 codeVerifier 필수, 형식 검사', async () => {
    const { call } = app();
    const missing = await call('POST', '/api/auth/google', { body: { code: 'c', redirectUri: 'https://x' } });
    expect(missing.status).toBe(400);
    expect(missing.json.message).toBe(KO['auth.social.code-verifier-required']);
    // 설정이 없으면 503 이 먼저(Spring SocialAuthService 와 같은 순서)
    const unconfigured = await app({ OAUTH_KAKAO_CLIENT_ID: '' }).call('POST', '/api/auth/kakao', {
      body: { code: 'c', redirectUri: 'https://x' },
    });
    expect(unconfigured.status).toBe(503);
    expect(
      (await call('POST', '/api/auth/kakao', { body: { code: 'c', redirectUri: 'https://x', codeVerifier: 'short' } })).json.message,
    ).toBe('codeVerifier: codeVerifier는 영문·숫자·-._~ 43~128자여야 합니다.');
  });

  it('동기화 요청 검증: activities[i].필드 경로와 Bean Validation 문구', async () => {
    const { call, services } = app();
    const token = (await services.jwt.issue('kakao:1')).accessToken;
    const res = await call('POST', '/api/health-activities/sync', {
      token,
      body: { date: '2026-10-20', activities: [{ type: 'STEPS', steps: -1 }] },
    });
    expect(res.json.message).toBe('activities[0].steps: 0 이상이어야 합니다.');
    const many = await call('POST', '/api/health-activities/sync', {
      token,
      body: { date: '2026-10-20', activities: Array.from({ length: 51 }, () => ({ type: 'STEPS', steps: 1 })) },
    });
    expect(many.json.message).toBe('activities: 한 번에 최대 50개의 활동까지 동기화할 수 있습니다.');
    const badEnum = await call('POST', '/api/health-activities/sync', {
      token,
      body: { date: '2026-10-20', activities: [{ type: 'SWIM' }] },
    });
    expect(badEnum.json.message).toBe('요청 본문을 읽을 수 없습니다. 형식을 확인해 주세요.');
    const missing = await call('GET', '/api/health-activities', { token });
    expect(missing.json.message).toBe('필수 요청 값이 빠졌습니다.');
    const invalid = await call('GET', '/api/health-activities?date=2026-13-01', { token });
    expect(invalid.json.message).toBe('요청 값의 형식이 올바르지 않습니다.');
  });

  it('레이트 리밋: 로그인 IP 당 분당 10회 → 429 + Retry-After', async () => {
    const { call } = app();
    for (let i = 0; i < 10; i++) {
      expect((await call('POST', '/api/auth/login', { body: { username: '', password: '' } })).status).toBe(400);
    }
    const limited = await call('POST', '/api/auth/login', { body: { username: '', password: '' } });
    expect(limited.status).toBe(429);
    expect(limited.headers.get('retry-after')).toBe('60');
    expect(limited.json.message).toBe('요청이 너무 많아요. 60초 후에 다시 시도해 주세요.');
    // 리프레시는 별도 버킷
    expect((await call('POST', '/api/auth/refresh', { body: {} })).status).toBe(400);
  });

  it('404·405 는 스프링 기본 오류 본문', async () => {
    const { call, services } = app();
    const token = (await services.jwt.issue('u')).accessToken;
    expect((await call('GET', '/nope')).json).toMatchObject({ status: 404, error: 'Not Found', path: '/nope' });
    expect((await call('GET', '/api/unknown')).status).toBe(401);
    expect((await call('GET', '/api/unknown', { token })).status).toBe(404);
    const wrong = await call('GET', '/api/auth/login');
    expect(wrong.status).toBe(405);
    expect(wrong.headers.get('allow')).toBe('POST');
  });

  it('API 문서는 설정으로 켜고 끈다(기본 꺼짐)', async () => {
    const on = await app().call('GET', '/v3/api-docs');
    expect(on.status).toBe(200);
    expect(Object.keys(on.json.paths)).toContain('/api/health-activities/sync');
    const ui = await app().call('GET', '/swagger-ui/index.html');
    expect(ui.status).toBe(200);
    expect(ui.headers.get('content-security-policy')).toContain('cdn.jsdelivr.net');
    expect((await app({ APP_API_DOCS_ENABLED: 'false' }).call('GET', '/v3/api-docs')).status).toBe(404);
  });
});
