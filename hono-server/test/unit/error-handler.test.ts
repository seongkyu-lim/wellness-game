import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Db } from '../../src/db/client.js';
import { ApiError, InternalError, UnreadableBodyError, badRequest, forbidden, serviceUnavailable, tooManyRequests, unauthorized } from '../../src/lib/errors.js';
import { errorBody, defaultErrorBody, resolve } from '../../src/lib/errors.js';
import { buildTestApp } from '../support/api.js';

const noDb = new Proxy({}, {
  get() {
    throw new Error('DB 를 쓰면 안 되는 테스트입니다.');
  },
}) as unknown as Db;

function app(env?: Record<string, string>) {
  const built = buildTestApp({ db: noDb, env });
  built.services.validity.isRevoked = async () => false;
  // 오류 처리기를 검증하기 위한 임의 경로(notFound/onError 는 나중에 등록한 라우트에도 적용된다)
  built.app.get('/boom/internal-400', () => {
    throw new InternalError(400, 'SECRET-DETAIL-400');
  });
  built.app.get('/boom/internal-503', () => {
    throw new InternalError(503, 'SECRET-DETAIL-503');
  });
  built.app.get('/boom/plain', () => {
    throw new Error('SECRET connection string postgres://user:pw@host');
  });
  built.app.get('/boom/unreadable', () => {
    throw new UnreadableBodyError('Cannot deserialize SECRET-FIELD');
  });
  built.app.get('/boom/api', () => {
    throw new ApiError(409, { code: 'auth.username-taken' }, { 'X-Custom': 'yes' });
  });
  return built;
}

describe('오류 처리기 계약', () => {
  let warn: ReturnType<typeof vi.spyOn>;
  let error: ReturnType<typeof vi.spyOn>;
  beforeEach(() => {
    warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    error = vi.spyOn(console, 'error').mockImplementation(() => {});
  });
  afterEach(() => vi.restoreAllMocks());

  const SHAPE = ['error', 'message', 'status', 'timestamp'];

  it('InternalError 는 상태만 유지하고 상세는 응답에 넣지 않는다(ko/en 일반 문구), 상세는 로그에만', async () => {
    const { call } = app();
    const ko = await call('GET', '/boom/internal-400');
    expect(ko.status).toBe(400);
    expect(ko.json).toMatchObject({ status: 400, error: 'Bad Request' });
    expect(JSON.stringify(ko.json)).not.toContain('SECRET');
    const en = await call('GET', '/boom/internal-503', { headers: { 'Accept-Language': 'en' } });
    expect(en.status).toBe(503);
    expect(en.json.error).toBe('Service Unavailable');
    expect(en.json.message).not.toBe(ko.json.message); // 로케일별 error.generic
    expect(JSON.stringify(en.json)).not.toContain('SECRET');
    expect(Object.keys(en.json).sort()).toEqual(SHAPE);
  });

  it('예상하지 못한 예외(Error)는 500 + 일반 문구, 연결 문자열 같은 내용은 새지 않는다', async () => {
    const { call } = app();
    for (const path of ['/boom/plain']) {
      const res = await call('GET', path);
      expect(res.status).toBe(500);
      expect(res.json).toMatchObject({ status: 500, error: 'Internal Server Error' });
      expect(JSON.stringify(res.json)).not.toMatch(/SECRET|postgres/);
      expect(Object.keys(res.json).sort()).toEqual(SHAPE);
    }
  });

  it('본문 해석 실패는 400 일반 문구(필드 이름을 드러내지 않음)', async () => {
    const { call } = app();
    const res = await call('GET', '/boom/unreadable');
    expect(res.status).toBe(400);
    expect(res.json.message).toBe('요청 본문을 읽을 수 없습니다. 형식을 확인해 주세요.');
    expect(JSON.stringify(res.json)).not.toContain('SECRET');
  });

  it('ApiError 는 상태·문구·사용자 지정 헤더를 그대로 내보내고, 타임스탬프는 ISO-8601', async () => {
    const { call } = app();
    const res = await call('GET', '/boom/api');
    expect(res.status).toBe(409);
    expect(res.headers.get('x-custom')).toBe('yes');
    expect(res.json).toMatchObject({ status: 409, error: 'Conflict', message: '이미 사용 중인 아이디입니다.' });
    expect(new Date(res.json.timestamp).toISOString()).toBe(res.json.timestamp);
  });

  it('로그 수준: 4xx 는 warn(스택 없이 한 줄), 5xx 는 error', async () => {
    const { call } = app();
    await call('GET', '/boom/api');
    expect(warn).toHaveBeenCalledTimes(1);
    expect(error).not.toHaveBeenCalled();
    await call('GET', '/boom/plain');
    expect(error).toHaveBeenCalledTimes(1);
    expect(String(error.mock.calls[0]![0])).toContain('500');
    // 4xx 로그에 요청 본문 조각이 없다
    warn.mockClear();
    await call('POST', '/api/auth/login', { body: { username: 'someone', password: 'hunter2-hunter2' } });
    expect(JSON.stringify(warn.mock.calls)).not.toContain('hunter2');
  });

  it('오류 응답에도 보안 헤더와 인증 응답의 no-store 가 붙는다', async () => {
    const { call } = app();
    const res = await call('POST', '/api/auth/login', { body: '{bad' });
    expect(res.status).toBe(400);
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(res.headers.get('x-content-type-options')).toBe('nosniff');
    expect(res.headers.get('x-frame-options')).toBe('DENY');
    const notFound = await call('GET', '/nope');
    expect(notFound.headers.get('x-content-type-options')).toBe('nosniff');
  });

  it('401·403·404·405·413·415·429 본문 형식', async () => {
    const { call, services } = app();
    const full = (await services.jwt.issue('u1')).accessToken;
    const read = (await services.jwt.issueWatch('u1')).accessToken;
    const bodyKeys = (r: { json: object }) => Object.keys(r.json).sort();

    const r401 = await call('GET', '/api/characters/me');
    expect([r401.status, r401.json.error, r401.headers.get('www-authenticate')]).toEqual([401, 'Unauthorized', 'Bearer']);
    expect(bodyKeys(r401)).toEqual(SHAPE);

    const r403 = await call('GET', '/api/characters/u2', { token: full });
    expect([r403.status, r403.json.error, r403.json.message]).toEqual([403, 'Forbidden', '다른 사용자의 데이터입니다.']);

    const r403Read = await call('POST', '/api/auth/watch-token', { token: read });
    expect(r403Read.status).toBe(403);

    const r404 = await call('GET', '/api/nothing-here', { token: full });
    expect(r404.json).toMatchObject({ status: 404, error: 'Not Found', path: '/api/nothing-here' });
    expect(Object.keys(r404.json).sort()).toEqual(['error', 'path', 'status', 'timestamp']);

    for (const [method, path, allow] of [
      ['GET', '/api/auth/refresh', 'POST'],
      ['POST', '/healthz', 'GET'],
      ['POST', '/api/health-activities', 'GET'],
      ['GET', '/api/health-activities/sync', 'POST'],
      ['POST', '/api/characters/me', 'GET'],
    ] as const) {
      const r = await call(method, path, { token: full });
      expect([r.status, r.headers.get('allow'), r.json.error], `${method} ${path}`).toEqual([405, allow, 'Method Not Allowed']);
    }

    const r415 = await call('POST', '/api/auth/login', { body: 'username=a', headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
    expect(r415.json).toMatchObject({ status: 415, error: 'Unsupported Media Type', path: '/api/auth/login' });

    const r413 = await call('POST', '/api/auth/login', { body: 'x'.repeat(16 * 1024 + 1), headers: { 'Content-Type': 'application/json' } });
    expect(r413.json).toMatchObject({ status: 413, error: 'Content Too Large', path: '/api/auth/login' });
  });
});

describe('레이트 리밋 미들웨어', () => {
  const login = (call: ReturnType<typeof app>['call'], headers: Record<string, string> = {}) =>
    call('POST', '/api/auth/login', { body: { username: '', password: '' }, headers });

  it('preflight(OPTIONS + Origin + Access-Control-Request-Method)는 세지 않는다', async () => {
    const { call } = app({ APP_RATE_LIMIT_LOGIN_PER_MINUTE: '2' });
    const preflight = { Origin: 'http://localhost:5173', 'Access-Control-Request-Method': 'POST' };
    for (let i = 0; i < 20; i++) expect((await call('OPTIONS', '/api/auth/login', { headers: preflight })).status).toBeLessThan(300);
    expect((await login(call)).status).toBe(400);
    expect((await login(call)).status).toBe(400);
    expect((await login(call)).status).toBe(429);
  });

  it('한도 0 은 제한 없음, 로그인·가입은 같은 버킷을 공유한다', async () => {
    const off = app({ APP_RATE_LIMIT_LOGIN_PER_MINUTE: '0', APP_RATE_LIMIT_AUTH_PER_MINUTE: '0' });
    for (let i = 0; i < 40; i++) expect((await login(off.call)).status).toBe(400);
    const shared = app({ APP_RATE_LIMIT_LOGIN_PER_MINUTE: '3' });
    await login(shared.call);
    await shared.call('POST', '/api/auth/signup', { body: {} });
    await login(shared.call);
    expect((await shared.call('POST', '/api/auth/signup', { body: {} })).status).toBe(429);
  });

  it('refresh 는 인증 일반 버킷에 세지 않고 자기 버킷만 쓴다', async () => {
    const { call } = app({ APP_RATE_LIMIT_AUTH_PER_MINUTE: '1', APP_RATE_LIMIT_REFRESH_PER_MINUTE: '3' });
    for (let i = 0; i < 3; i++) expect((await call('POST', '/api/auth/refresh', { body: {} })).status).toBe(400);
    expect((await call('POST', '/api/auth/refresh', { body: {} })).status).toBe(429);
  });

  it('전체(general) 한도를 켜면 /api 전 경로(404 포함)에 적용되고 /healthz 는 제외', async () => {
    const { call } = app({ APP_RATE_LIMIT_GENERAL_PER_MINUTE: '2' });
    expect((await call('GET', '/api/x')).status).toBe(401);
    expect((await call('GET', '/api/y')).status).toBe(401);
    const limited = await call('GET', '/api/z');
    expect(limited.status).toBe(429);
    expect(limited.headers.get('retry-after')).toBe('60');
    for (let i = 0; i < 5; i++) expect((await call('GET', '/healthz')).status).toBe(200);
  });

  it('동기화 한도는 사용자별: 한 사용자가 한도를 써도 다른 사용자는 영향이 없다', async () => {
    const { call, services } = app({ APP_RATE_LIMIT_SYNC_PER_MINUTE: '2' });
    const a = (await services.jwt.issue('user-a')).accessToken;
    const b = (await services.jwt.issue('user-b')).accessToken;
    const sync = (token: string) => call('POST', '/api/health-activities/sync', { token, body: {} });
    expect((await sync(a)).status).toBe(400);
    expect((await sync(a)).status).toBe(400);
    expect((await sync(a)).status).toBe(429);
    expect((await sync(b)).status).toBe(400);
    // GET 은 세지 않는다
    expect((await call('GET', '/api/health-activities?date=2026-10-20', { token: a })).status).not.toBe(429);
  });

  it('주입한 시계를 넘기면 윈도우가 새로 열린다', async () => {
    const { call, clock } = app({ APP_RATE_LIMIT_LOGIN_PER_MINUTE: '1' });
    expect((await login(call)).status).toBe(400);
    const limited = await login(call);
    expect(limited.status).toBe(429);
    clock.advance(59_000);
    expect((await login(call)).headers.get('retry-after')).toBe('1');
    clock.advance(1000);
    expect((await login(call)).status).toBe(400);
  });

  it('X-Forwarded-For 는 신뢰 설정이 켜진 때만 키로 쓴다(꺼져 있으면 위조해도 같은 소켓 주소로 센다)', async () => {
    const off = app({ APP_RATE_LIMIT_LOGIN_PER_MINUTE: '1' });
    expect((await login(off.call, { 'X-Forwarded-For': '1.1.1.1' })).status).toBe(400);
    expect((await login(off.call, { 'X-Forwarded-For': '2.2.2.2' })).status).toBe(429);
    const on = app({ APP_RATE_LIMIT_LOGIN_PER_MINUTE: '1', APP_RATE_LIMIT_TRUST_FORWARDED_HEADER: 'true' });
    expect((await login(on.call, { 'X-Forwarded-For': '1.1.1.1' })).status).toBe(400);
    expect((await login(on.call, { 'X-Forwarded-For': '2.2.2.2' })).status).toBe(400);
    expect((await login(on.call, { 'X-Forwarded-For': '9.9.9.9, 1.1.1.1' })).status).toBe(429); // 오른쪽 끝(프록시가 덧붙인 값)
  });
});

describe('헤더 설정', () => {
  it('HSTS 값이 비어 있으면 헤더를 보내지 않는다', async () => {
    const { call } = app({ APP_HSTS_HEADER: '' });
    expect((await call('GET', '/healthz')).headers.get('strict-transport-security')).toBeNull();
  });

  it('CORS 허용 목록에 *가 있으면 모든 출처를 허용한다', async () => {
    const { call } = app({ CORS_ALLOWED_ORIGINS: '*' });
    const res = await call('GET', '/api/characters/me', { headers: { Origin: 'https://anything.example' } });
    expect(res.status).toBe(401);
    expect(res.headers.get('access-control-allow-origin')).toBe('*');
  });

  it('같은 출처 요청(Origin 이 요청 주소와 같음)은 허용 목록에 없어도 CORS 로 막지 않는다', async () => {
    const { call } = app();
    const res = await call('GET', '/api/characters/me', { headers: { Origin: 'http://localhost' } });
    expect(res.status).toBe(401);
  });
});

describe('오류 헬퍼', () => {
  it('상태 코드·헤더·문구 인자', () => {
    expect(badRequest('x').status).toBe(400);
    expect(unauthorized('x').headers).toEqual({ 'WWW-Authenticate': 'Bearer' });
    expect(forbidden('x').status).toBe(403);
    expect(serviceUnavailable('x').status).toBe(503);
    const limited = tooManyRequests(7);
    expect(limited).toMatchObject({ status: 429, headers: { 'Retry-After': '7' } });
    expect(resolve('en', limited.localized)).toBe('Too many requests. Please try again in 7 seconds.');
  });

  it('resolve: prefix 와 {이름} 치환, 같은 이름이 여러 번 나와도 모두 치환', () => {
    expect(resolve('ko', { code: 'validation.password.bytes', prefix: 'pw: ', named: { value: 72 } })).toBe(
      'pw: 비밀번호는 UTF-8 기준 72바이트 이하여야 합니다(한글은 글자당 3바이트).',
    );
    expect(resolve('ko', { code: 'unknown.code', prefix: '> ' })).toBe('> unknown.code');
  });

  it('errorBody/defaultErrorBody: 모르는 상태는 "Error"', () => {
    expect(errorBody(418, 'teapot')).toMatchObject({ status: 418, error: 'Error', message: 'teapot' });
    expect(defaultErrorBody(418, '/x')).toMatchObject({ status: 418, error: 'Error', path: '/x' });
  });
});
