import { describe, expect, it } from 'vitest';
import { message } from '../../src/lib/i18n.js';
import { EN, KO } from '../../src/lib/messages-data.js';
import { describeDb } from '../support/api.js';
import { signUp, stepsBody, useApi } from '../support/flow.js';

/**
 * 실제 DB 를 거치는 경로의 오류 응답 계약: 상태 코드·본문 형식·ko/en 문구.
 * 문구는 messages-data 의 키로 비교해(하드코딩 문자열 중복 방지) 두 로케일이 모두 해석되는지 본다.
 */
const SHAPE = ['error', 'message', 'status', 'timestamp'];
const LOCALES = [
  ['ko', KO, undefined],
  ['en', EN, 'en-US,en;q=0.9'],
] as const;

describeDb('오류 응답 계약(PostgreSQL)', () => {
  const api = useApi({ env: { APP_RATE_LIMIT_LOGIN_PER_MINUTE: '0' } });

  describe.each(LOCALES)('%s', (name, table, acceptLanguage) => {
    const headers = (): Record<string, string> => (acceptLanguage ? { 'Accept-Language': acceptLanguage } : {});
    const expectError = (res: { status: number; json: any }, status: number, code: string, reason: string) => {
      expect(res.status).toBe(status);
      expect(Object.keys(res.json).sort()).toEqual(SHAPE);
      expect(res.json).toMatchObject({ status, error: reason, message: table[code] });
    };

    it('400: 아이디 중복·로그인 실패(사유 구분 없음)·재사용 아닌 형식 오류', async () => {
      const { call } = api.build();
      await signUp(call, 'dupe_name');
      const taken = await call('POST', '/api/auth/signup', { body: { username: 'DUPE_NAME', password: 'password123' }, headers: headers() });
      expectError(taken, 400, 'auth.username-taken', 'Bad Request');
      for (const body of [
        { username: 'dupe_name', password: 'wrong-password' }, // 틀린 비밀번호
        { username: 'nobody_here', password: 'password123' }, // 없는 아이디
        { username: 'x', password: 'password123' }, // 규칙에 안 맞는 아이디
      ]) {
        const bad = await call('POST', '/api/auth/login', { body, headers: headers() });
        expectError(bad, 400, 'auth.invalid-credentials', 'Bad Request');
      }
      // 잠금 중에도 같은 문구(잠금 여부를 알려 주지 않는다)
      const locked = api.build({ APP_AUTH_MAX_FAILED_LOGINS: '1' });
      await locked.call('POST', '/api/auth/login', { body: { username: 'dupe_name', password: 'bad-bad-bad' } });
      const blocked = await locked.call('POST', '/api/auth/login', { body: { username: 'dupe_name', password: 'password123' }, headers: headers() });
      expectError(blocked, 400, 'auth.invalid-credentials', 'Bad Request');
    });

    it('401: 인증 헤더·토큰·세션 해제·리프레시 토큰의 각 사유', async () => {
      const { call } = api.build();
      const session = await signUp(call);
      expectError(await call('GET', '/api/characters/me', { headers: headers() }), 401, 'auth.login-required', 'Unauthorized');
      expectError(await call('GET', '/api/characters/me', { token: 'bad.token.value', headers: headers() }), 401, 'auth.token-invalid', 'Unauthorized');
      const unknown = await call('POST', '/api/auth/refresh', { body: { refreshToken: 'nope' }, headers: headers() });
      expectError(unknown, 401, 'auth.refresh.invalid', 'Unauthorized');
      expect(unknown.headers.get('www-authenticate')).toBe('Bearer');

      api.clock.advance(2000);
      const rotated = await call('POST', '/api/auth/refresh', { body: { refreshToken: session.refreshToken } });
      api.clock.advance(60_000);
      expectError(
        await call('POST', '/api/auth/refresh', { body: { refreshToken: session.refreshToken }, headers: headers() }),
        401,
        'auth.refresh.reused',
        'Unauthorized',
      );
      api.clock.advance(1000);
      expectError(await call('GET', '/api/characters/me', { token: rotated.json.accessToken, headers: headers() }), 401, 'auth.session-revoked', 'Unauthorized');
      api.clock.advance(3_600_000);
      const fresh = await call('POST', '/api/auth/login', { body: { username: 'tester_01', password: 'password123' } });
      api.clock.advance(3_600_000);
      expectError(await call('GET', '/api/characters/me', { token: fresh.json.accessToken, headers: headers() }), 401, 'auth.token-expired', 'Unauthorized');
      api.clock.advance(31 * 86_400_000);
      expectError(
        await call('POST', '/api/auth/refresh', { body: { refreshToken: fresh.json.refreshToken }, headers: headers() }),
        401,
        'auth.refresh.expired',
        'Unauthorized',
      );
    });

    it('403: 다른 사용자 데이터(경로·쿼리·본문), 조회 전용 토큰의 쓰기', async () => {
      const { call } = api.build();
      const session = await signUp(call);
      for (const [method, path, body] of [
        ['GET', '/api/characters/someone:else', undefined],
        ['GET', '/api/health-activities?date=2026-10-20&userId=someone:else', undefined],
        ['POST', '/api/health-activities/sync', { ...stepsBody(100), userId: 'someone:else' }],
      ] as const) {
        const res = await call(method, path, { token: session.accessToken, body, headers: headers() });
        expectError(res, 403, 'auth.forbidden', 'Forbidden');
      }
      // 본인 userId 를 명시하는 것은 허용
      const self = await call('GET', `/api/characters/${session.userId}`, { token: session.accessToken });
      expect(self.status).toBe(404);
      const watch = await call('POST', '/api/auth/watch-token', { token: session.accessToken });
      const readOnly = await call('POST', '/api/health-activities/sync', { token: watch.json.accessToken, body: stepsBody(100), headers: headers() });
      expectError(readOnly, 403, 'auth.read-only-token', 'Forbidden');
    });

    it('404: 캐릭터가 없으면 본문 없이 404, 없는 경로는 기본 오류 본문', async () => {
      const { call } = api.build();
      const session = await signUp(call);
      const none = await call('GET', '/api/characters/me', { token: session.accessToken, headers: headers() });
      expect(none.status).toBe(404);
      expect(none.json).toBeNull();
      const route = await call('GET', '/api/no-such', { token: session.accessToken, headers: headers() });
      expect(route.json).toMatchObject({ status: 404, error: 'Not Found', path: '/api/no-such' });
    });

    it('405: Allow 헤더와 기본 오류 본문(메시지 필드 없음, 로케일 무관)', async () => {
      const { call } = api.build();
      const session = await signUp(call);
      const res = await call('DELETE', '/api/characters/me', { token: session.accessToken, headers: headers() });
      expect(res.status).toBe(405);
      expect(res.headers.get('allow')).toBe('GET');
      expect(Object.keys(res.json).sort()).toEqual(['error', 'path', 'status', 'timestamp']);
    });

    it('413·415: 본문 크기 초과와 JSON 이 아닌 Content-Type', async () => {
      const { call } = api.build();
      const session = await signUp(call);
      const big = await call('POST', '/api/health-activities/sync', {
        token: session.accessToken,
        body: JSON.stringify({ date: '2026-10-20', activities: [{ type: 'STEPS', steps: 1, pad: 'x'.repeat(256 * 1024) }] }),
        headers: { 'Content-Type': 'application/json', ...headers() },
      });
      expect(big.status).toBe(413);
      expect(big.json).toMatchObject({ status: 413, error: 'Content Too Large', path: '/api/health-activities/sync' });
      const wrongType = await call('POST', '/api/health-activities/sync', {
        token: session.accessToken,
        body: 'date=2026-10-20',
        headers: { 'Content-Type': 'text/plain', ...headers() },
      });
      expect(wrongType.json).toMatchObject({ status: 415, error: 'Unsupported Media Type' });
    });

    it('429: 동기화·로그인·리프레시 한도 초과는 Retry-After 와 남은 초 문구', async () => {
      const { call } = api.build({ APP_RATE_LIMIT_SYNC_PER_MINUTE: '2', APP_RATE_LIMIT_LOGIN_PER_MINUTE: '2', APP_RATE_LIMIT_REFRESH_PER_MINUTE: '2' });
      const session = await signUp(call);
      const sync = () => call('POST', '/api/health-activities/sync', { token: session.accessToken, body: stepsBody(100), headers: headers() });
      await sync();
      await sync();
      const limited = await sync();
      expect(limited.status).toBe(429);
      expect(limited.headers.get('retry-after')).toBe('60');
      expect(Object.keys(limited.json).sort()).toEqual(SHAPE);
      expect(limited.json).toMatchObject({ status: 429, error: 'Too Many Requests' });
      expect(limited.json.message).toBe(message(name, 'error.rate-limited', 60));
      // 로그인·리프레시도 같은 형식(각자 별도 버킷)
      const login = () => call('POST', '/api/auth/login', { body: { username: 'tester_01', password: 'bad-password' }, headers: headers() });
      await login();
      await login();
      expect((await login()).status).toBe(429);
      const refresh = () => call('POST', '/api/auth/refresh', { body: { refreshToken: 'unknown' }, headers: headers() });
      await refresh();
      await refresh();
      const refreshLimited = await refresh();
      expect(refreshLimited).toMatchObject({ status: 429 });
      expect(refreshLimited.headers.get('retry-after')).toBe('60');
      api.clock.advance(60_000); // 윈도우가 지나면 다시 허용
      expect((await sync()).status).toBe(200);
    });
  });
});

describe('문구 키 존재', () => {
  it('계약 테스트가 쓰는 키가 ko·en 양쪽에 있다', () => {
    for (const key of ['auth.username-taken', 'auth.invalid-credentials', 'auth.login-required', 'auth.token-invalid', 'auth.token-expired', 'auth.session-revoked', 'auth.refresh.invalid', 'auth.refresh.reused', 'auth.refresh.expired', 'auth.forbidden', 'auth.read-only-token']) {
      expect(KO, key).toHaveProperty([key]);
      expect(EN, key).toHaveProperty([key]);
    }
  });
});
