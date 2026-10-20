import { SignJWT, exportJWK, generateKeyPair } from 'jose';
import { afterAll, beforeAll, beforeEach, expect, it } from 'vitest';
import type { FetchFn } from '../../src/auth/http-client.js';
import type { Db } from '../../src/db/client.js';
import { buildTestApp, describeDb, openDb, resetSchema } from '../support/api.js';
import { MutableClock, T0 } from '../support/fixtures.js';

/**
 * 실제 PostgreSQL(DATABASE_URL_TEST)에 backend/db/schema.sql 을 만든 뒤 API 를 끝까지 호출한다.
 * DATABASE_URL_TEST 가 없으면 건너뛴다.
 */
describeDb('API(PostgreSQL)', () => {
  let db: Db;
  let clock: MutableClock;
  let fetchLog: { url: string; body: string }[];
  let fakeFetch: FetchFn;
  let google: { kid: string; privateKey: CryptoKey; jwks: unknown };

  beforeAll(async () => {
    db = openDb();
    const { publicKey, privateKey } = await generateKeyPair('RS256', { extractable: true });
    const jwk = await exportJWK(publicKey);
    google = { kid: 'test-kid', privateKey, jwks: { keys: [{ ...jwk, kid: 'test-kid', alg: 'RS256', use: 'sig' }] } };
  });

  afterAll(async () => {
    await db?.end();
  });

  beforeEach(async () => {
    await resetSchema(db);
    clock = new MutableClock(T0);
    fetchLog = [];
    fakeFetch = async (url, init) => {
      const body = typeof init?.body === 'string' ? init.body : '';
      fetchLog.push({ url, body });
      if (url === 'https://oauth2.googleapis.com/token') return Response.json({ access_token: 'google-access' });
      if (url === 'https://openidconnect.googleapis.com/v1/userinfo') return Response.json({ sub: 'g-123', name: '구글 사용자' });
      if (url === 'https://www.googleapis.com/oauth2/v3/certs') return Response.json(google.jwks);
      return new Response('not found', { status: 404 });
    };
  });

  const build = () => buildTestApp({ db, clock, fetch: fakeFetch });

  async function signUp(call: ReturnType<typeof build>['call'], username = 'Tester_01') {
    const res = await call('POST', '/api/auth/signup', { body: { username, password: 'password123', displayName: '테스터' } });
    expect(res.status).toBe(200);
    return res.json;
  }

  it('가입·로그인: 소문자 정규화, 대소문자만 다른 가입은 중복, 응답 필드', async () => {
    const { call } = build();
    const created = await signUp(call);
    expect(Object.keys(created)).toEqual([
      'userIdentifier',
      'userId',
      'displayName',
      'accessToken',
      'tokenType',
      'expiresIn',
      'refreshToken',
      'refreshExpiresIn',
    ]);
    expect(created.userId).toBe(`password:${created.userIdentifier}`);
    expect(created).toMatchObject({ displayName: '테스터', tokenType: 'Bearer', expiresIn: 3600, refreshExpiresIn: 30 * 86400 });
    const [row] = await db`select username, password_hash from user_credential`;
    expect(row!.username).toBe('tester_01');
    expect(row!.password_hash).toMatch(/^\$2[aby]\$10\$/);

    expect((await call('POST', '/api/auth/signup', { body: { username: 'TESTER_01', password: 'password123' } })).json.message).toBe(
      '이미 사용 중인 아이디입니다.',
    );
    const login = await call('POST', '/api/auth/login', { body: { username: ' TeStEr_01 ', password: 'password123' } });
    expect(login.status).toBe(200);
    expect(login.json.userId).toBe(created.userId);
    const wrong = await call('POST', '/api/auth/login', { body: { username: 'tester_01', password: 'nope-nope' } });
    expect(wrong.json).toMatchObject({ status: 400, message: '아이디 또는 비밀번호가 올바르지 않습니다.' });
  });

  it('Spring 이 만든 BCrypt($2a$) 해시로 로그인된다', async () => {
    const { call } = build();
    // Spring BCryptPasswordEncoder 로 "password123" 을 해시한 값과 같은 형식($2a$10$)
    const bcrypt = (await import('bcryptjs')).default;
    const hash = (await bcrypt.hash('password123', 10)).replace(/^\$2b\$/, '$2a$');
    await db`insert into user_credential (id, username, password_hash, user_identifier, display_name, created_at, updated_at)
             values (gen_random_uuid(), 'legacy_user', ${hash}, 'legacy-id', null, now(), now())`;
    const login = await call('POST', '/api/auth/login', { body: { username: 'legacy_user', password: 'password123' } });
    expect(login.json).toMatchObject({ userId: 'password:legacy-id', displayName: null });
  });

  it('로그인 잠금: (아이디, IP) 5회 실패 뒤 올바른 비밀번호도 거부, 규칙에 안 맞는 아이디도 실패로 센다', async () => {
    const { call } = build();
    await signUp(call, 'locked_user');
    for (let i = 0; i < 5; i++) await call('POST', '/api/auth/login', { body: { username: 'LOCKED_USER', password: 'wrong-pass' } });
    const blocked = await call('POST', '/api/auth/login', { body: { username: 'locked_user', password: 'password123' } });
    expect(blocked.status).toBe(400);
    clock.advance(15 * 60_000);
    expect((await call('POST', '/api/auth/login', { body: { username: 'locked_user', password: 'password123' } })).status).toBe(200);
  });

  it('리프레시 회전·유예 1회·재사용 감지·모든 기기 로그아웃', async () => {
    const { call } = build();
    const session = await signUp(call);
    clock.advance(2000);
    const rotated = await call('POST', '/api/auth/refresh', { body: { refreshToken: session.refreshToken } });
    expect(rotated.status).toBe(200);
    expect(Object.keys(rotated.json)).toEqual(['userId', 'accessToken', 'tokenType', 'expiresIn', 'refreshToken', 'refreshExpiresIn']);
    clock.advance(1000);
    expect((await call('POST', '/api/auth/refresh', { body: { refreshToken: session.refreshToken } })).status).toBe(200);
    const reused = await call('POST', '/api/auth/refresh', { body: { refreshToken: session.refreshToken } });
    expect(reused.json).toMatchObject({ status: 401, message: '이미 사용된 리프레시 토큰입니다. 보안을 위해 다시 로그인해 주세요.' });
    // 재사용 감지 전에 발급된 액세스 토큰은 무효
    const me = await call('GET', '/api/characters/me', { token: rotated.json.accessToken });
    expect(me.json.message).toBe('로그인이 해제되었습니다. 다시 로그인해 주세요.');
    expect((await call('POST', '/api/auth/refresh', { body: { refreshToken: rotated.json.refreshToken } })).status).toBe(401);

    clock.advance(2000);
    const again = await call('POST', '/api/auth/login', { body: { username: 'tester_01', password: 'password123' } });
    clock.advance(1000); // 같은 초에 발급된 토큰은 기준에 걸리지 않는다(Spring 과 같음)
    expect((await call('POST', '/api/auth/logout-all', { token: again.json.accessToken })).status).toBe(204);
    expect((await call('GET', '/api/characters/me', { token: again.json.accessToken })).status).toBe(401);
  });

  it('이 기기 로그아웃(full family)은 Watch 토큰을 무효화하고, 웹(read family) 로그아웃은 하지 않는다', async () => {
    const { call } = build();
    const session = await signUp(call);
    const watch = await call('POST', '/api/auth/watch-token', { token: session.accessToken });
    expect(watch.json).toMatchObject({ tokenType: 'Bearer', expiresIn: 7 * 86400 });
    expect((await call('GET', '/api/characters/me', { token: watch.json.accessToken })).status).toBe(404); // 캐릭터 아직 없음

    // 웹 로그인(read family) 을 만들고 로그아웃해도 Watch 토큰은 그대로
    const web = await call('POST', '/api/auth/google', {
      body: { code: 'auth-code', redirectUri: 'http://localhost:5173/callback', codeVerifier: 'v'.repeat(43) },
    });
    expect(web.status).toBe(200);
    expect((await call('POST', '/api/auth/logout', { body: { refreshToken: web.json.refreshToken } })).status).toBe(204);
    expect((await call('GET', '/api/characters/me', { token: watch.json.accessToken })).status).toBe(404);

    expect((await call('POST', '/api/auth/logout', { body: { refreshToken: session.refreshToken } })).status).toBe(204);
    expect((await call('GET', '/api/characters/me', { token: watch.json.accessToken })).json.message).toBe(
      '로그인이 해제되었습니다. 다시 로그인해 주세요.',
    );
    // 전체 권한 액세스 토큰은 짧은 수명에 맡긴다
    expect((await call('GET', '/api/characters/me', { token: session.accessToken })).status).toBe(404);
    // 없는 토큰도 같은 204
    expect((await call('POST', '/api/auth/logout', { body: { refreshToken: 'unknown' } })).status).toBe(204);
    const [state] = await db`select tokens_valid_after, read_tokens_valid_after from user_auth_state`;
    expect(state!.tokens_valid_after.toString()).toBe('1970-01-01T00:00:00Z');
  });

  it('웹 구글 로그인: code_verifier 를 토큰 교환에 넘기고 조회 전용 토큰·read family 를 준다', async () => {
    const { call } = build();
    const web = await call('POST', '/api/auth/google', {
      body: { code: 'auth-code', redirectUri: 'http://localhost:5173/callback', codeVerifier: 'v'.repeat(43) },
    });
    expect(web.json).toMatchObject({ userId: 'google:g-123', displayName: '구글 사용자', expiresIn: 3600, refreshExpiresIn: 7 * 86400 });
    const form = new URLSearchParams(fetchLog[0]!.body);
    expect(Object.fromEntries(form)).toEqual({
      grant_type: 'authorization_code',
      client_id: 'web-client.apps.googleusercontent.com',
      client_secret: 'test-secret',
      redirect_uri: 'http://localhost:5173/callback',
      code: 'auth-code',
      code_verifier: 'v'.repeat(43),
    });
    const sync = await call('POST', '/api/health-activities/sync', {
      token: web.json.accessToken,
      body: { date: '2026-10-20', activities: [{ type: 'STEPS', steps: 100 }] },
    });
    expect(sync.status).toBe(403);
    const refreshed = await call('POST', '/api/auth/refresh', { body: { refreshToken: web.json.refreshToken } });
    expect((await call('POST', '/api/auth/watch-token', { token: refreshed.json.accessToken })).status).toBe(403);
    const [row] = await db`select scope from refresh_token where revoked_at is null`;
    expect(row!.scope).toBe('read');
  });

  it('구글 네이티브: JWKS 로 서명 검증, 웹 클라이언트 ID 는 aud 로 받지 않는다', async () => {
    const { call } = build();
    const idToken = (aud: string) =>
      new SignJWT({ name: 'Native' })
        .setProtectedHeader({ alg: 'RS256', kid: google.kid })
        .setIssuer('https://accounts.google.com')
        .setAudience(aud)
        .setSubject('g-native')
        .setIssuedAt(T0 / 1000)
        .setExpirationTime(T0 / 1000 + 600)
        .sign(google.privateKey);
    const web = await call('POST', '/api/auth/google/native', { body: { idToken: await idToken('web-client.apps.googleusercontent.com') } });
    expect(web.json.message).toBe('이 앱에 발급된 구글 ID 토큰이 아닙니다.');
    const ok = await call('POST', '/api/auth/google/native', { body: { idToken: await idToken('android-server.apps.googleusercontent.com') } });
    expect(ok.json).toMatchObject({ userId: 'google:g-native', displayName: 'Native' });
    clock.advance(601_000);
    const expired = await call('POST', '/api/auth/google/native', { body: { idToken: await idToken('ios-client.apps.googleusercontent.com') } });
    expect(expired.json.message).toBe('구글 ID 토큰이 만료되었습니다. 다시 로그인해 주세요.');
  });

  it('건강 동기화: XP·레벨·스탯·목표, 재전송은 중복, 5분 미만 운동은 기록만', async () => {
    const { call } = build();
    const session = await signUp(call);
    const body = {
      date: '2026-10-20',
      activities: [
        { type: 'STEPS', steps: 8500 },
        {
          type: 'WORKOUT',
          workoutType: 'RUNNING',
          calories: 300,
          distanceMeters: 5000,
          startedAt: '2026-10-20T00:00:00Z',
          endedAt: '2026-10-20T00:30:00Z',
        },
        { type: 'SLEEP', sleepMinutes: 480, startedAt: '2026-10-19T14:00:00Z', endedAt: '2026-10-19T22:00:00Z' },
        { type: 'WORKOUT', workoutType: 'SWIMMING', startedAt: '2026-10-20T01:00:00Z', endedAt: '2026-10-20T01:04:00Z' },
      ],
    };
    const res = await call('POST', '/api/health-activities/sync', { token: session.accessToken, body });
    expect(res.status).toBe(200);
    expect(res.json).toMatchObject({
      userId: session.userId,
      date: '2026-10-20',
      gainedXp: 70 + 135 + 80,
      levelUp: true,
      character: { level: 3, currentXp: 35, totalXp: 285, nextLevelXp: 200, stats: { str: 1, vit: 2, intStat: 2, discipline: 2, recovery: 1 } },
      goals: [
        { type: 'STEPS', target: 8000, current: 8500, unit: 'steps', achieved: true },
        { type: 'WORKOUT', target: 30, current: 34, unit: 'minutes', achieved: true },
        { type: 'SLEEP', target: 420, current: 480, unit: 'minutes', achieved: true },
      ],
    });
    expect(res.json.activityResults.map((r: { message: string }) => r.message)).toEqual([
      '걸음 수 보상 +70 XP',
      '달리기 완료 +135 XP',
      '수면 회복 보너스 +80 XP',
      '수영 기록됨 (5분 미만 운동은 XP·스탯이 없어요)',
    ]);

    const again = await call('POST', '/api/health-activities/sync', {
      token: session.accessToken,
      body,
      headers: { 'Accept-Language': 'en' },
    });
    expect(again.json.gainedXp).toBe(0);
    expect(again.json.activityResults.map((r: { duplicate: boolean }) => r.duplicate)).toEqual([true, true, true, true]);
    expect(again.json.activityResults[1].message).toBe('Running already counted');

    const daily = await call('GET', '/api/health-activities?date=2026-10-20', { token: session.accessToken });
    expect(daily.json.activities.map((a: { type: string; startedAt: string | null }) => [a.type, a.startedAt])).toEqual([
      ['STEPS', null],
      ['SLEEP', '2026-10-19T14:00:00Z'],
      ['WORKOUT', '2026-10-20T00:00:00Z'],
      ['WORKOUT', '2026-10-20T01:00:00Z'],
    ]);
    expect(daily.json.activities[2]).toMatchObject({ source: 'RUNNING', durationMinutes: 30, calories: 300, gainedXp: 135 });

    const me = await call('GET', '/api/characters/me', { token: session.accessToken });
    expect(me.json).toEqual({ userId: session.userId, character: res.json.character });
    const other = await call('GET', '/api/health-activities?date=2026-10-20&userId=kakao:999', { token: session.accessToken });
    expect(other.status).toBe(403);
  });

  it('같은 수면을 다른 날짜로 다시 보내면 건너뛴다(#107)', async () => {
    const { call } = build();
    const session = await signUp(call);
    const sleep = { type: 'SLEEP', sleepMinutes: 480, startedAt: '2026-10-19T14:00:00Z', endedAt: '2026-10-19T22:00:00Z' };
    await call('POST', '/api/health-activities/sync', { token: session.accessToken, body: { date: '2026-10-20', activities: [sleep] } });
    const other = await call('POST', '/api/health-activities/sync', {
      token: session.accessToken,
      body: { date: '2026-10-19', activities: [sleep] },
    });
    expect(other.json.gainedXp).toBe(0);
    expect(other.json.activityResults[0]).toMatchObject({ duplicate: true, message: '수면 다른 날짜로 이미 반영됨' });
  });
});
