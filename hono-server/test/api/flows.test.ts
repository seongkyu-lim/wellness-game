import { createHash } from 'node:crypto';
import { SignJWT } from 'jose';
import { beforeAll, expect, it } from 'vitest';
import { describeDb } from '../support/api.js';
import { T0 } from '../support/fixtures.js';
import { signUp, stepsBody, useApi, workoutAt } from '../support/flow.js';
import { jwksOf, json, makeKey, stubFetch, type TestKey } from '../support/oauth.js';
import { SPRING_BCRYPT, SPRING_FULL_TOKEN, SPRING_LEGACY_TOKEN, SPRING_READ_TOKEN } from '../support/spring-fixtures.js';

const DAY = 86_400_000;
const REVOKED = '로그인이 해제되었습니다. 다시 로그인해 주세요.';

let google: TestKey;
beforeAll(async () => {
  google = await makeKey('g-kid');
});

const fakeFetch = () =>
  stubFetch({
    'https://oauth2.googleapis.com/token': json({ access_token: 'google-access' }),
    'https://openidconnect.googleapis.com/v1/userinfo': json({ sub: 'g-web', name: '웹 사용자' }),
    'https://www.googleapis.com/oauth2/v3/certs': json(jwksOf(google)),
  }).fn;

describeDb('전체 흐름(PostgreSQL)', () => {
  const api = useApi({ fetch: fakeFetch });

  it('가입 → 로그인 → 동기화 → 캐릭터 조회 → 일별 조회가 같은 데이터를 본다', async () => {
    const { call } = api.build();
    const signup = await signUp(call);
    const login = await call('POST', '/api/auth/login', { body: { username: 'tester_01', password: 'password123' } });
    expect(login.status).toBe(200);
    expect(login.json.userId).toBe(signup.userId);

    const sync = await call('POST', '/api/health-activities/sync', {
      token: login.json.accessToken,
      body: { date: '2026-10-20', activities: [{ type: 'STEPS', steps: 8000 }, workoutAt(0, { minutes: 30, type: 'RUNNING' })] },
    });
    expect(sync.status).toBe(200);
    expect(sync.json.gainedXp).toBe(70 + (30 * 3 + 10 + 15));
    expect(sync.json.character).toMatchObject({ level: 2, totalXp: 185, currentXp: 85 });

    const me = await call('GET', '/api/characters/me', { token: login.json.accessToken });
    const byId = await call('GET', `/api/characters/${signup.userId}`, { token: login.json.accessToken });
    expect(me.json).toEqual({ userId: signup.userId, character: sync.json.character });
    expect(byId.json).toEqual(me.json);

    const daily = await call('GET', '/api/health-activities?date=2026-10-20', { token: login.json.accessToken });
    expect(daily.json.activities.map((a: { type: string; gainedXp: number }) => [a.type, a.gainedXp])).toEqual([
      ['STEPS', 70],
      ['WORKOUT', 115],
    ]);
    // 다른 날짜는 비어 있다
    const other = await call('GET', '/api/health-activities?date=2026-10-19', { token: login.json.accessToken });
    expect(other.json.activities).toEqual([]);
  });

  it('걸음 수를 늘려 다시 보내면 차액만 지급하고 레벨·스탯이 누적된다', async () => {
    const { call } = api.build();
    const { accessToken } = await signUp(call);
    const post = (steps: number) => call('POST', '/api/health-activities/sync', { token: accessToken, body: stepsBody(steps) });
    expect((await post(3000)).json.gainedXp).toBe(15);
    expect((await post(3000)).json.gainedXp).toBe(0); // 같은 값은 중복
    expect((await post(2000)).json.gainedXp).toBe(0); // 줄어든 값도 중복으로 취급
    const up = await post(8000);
    expect(up.json.gainedXp).toBe(55); // 70 - 15
    expect(up.json.character.stats.discipline).toBe(2); // 첫 지급 +1, 목표 달성 +1
    expect((await post(8000)).json.activityResults[0].duplicate).toBe(true);
  });

  it('리프레시 회전 체인: 유휴 만료는 매번 연장되지만 절대 만료(90일)를 넘지 않는다', async () => {
    const { call } = api.build();
    let { refreshToken } = await signUp(call);
    const lifetimes: number[] = [];
    for (let i = 0; i < 3; i++) {
      api.clock.advance(29 * DAY);
      const res = await call('POST', '/api/auth/refresh', { body: { refreshToken } });
      expect(res.status).toBe(200);
      lifetimes.push(res.json.refreshExpiresIn);
      expect(res.json.refreshToken).not.toBe(refreshToken);
      refreshToken = res.json.refreshToken;
    }
    // 29·58 일째는 30일 연장, 87 일째는 절대 만료(90일째)까지 3일만 남는다
    expect(lifetimes).toEqual([30 * 86400, 30 * 86400, 3 * 86400]);
    api.clock.advance(4 * DAY); // 91 일째: 절대 만료 이후
    const late = await call('POST', '/api/auth/refresh', { body: { refreshToken } });
    expect(late.json.message).toBe('리프레시 토큰이 만료되었습니다. 다시 로그인해 주세요.');
  });

  it('유휴 30일이 지난 리프레시 토큰은 만료(401)', async () => {
    const { call } = api.build();
    const { refreshToken } = await signUp(call);
    api.clock.advance(30 * DAY - 1000);
    expect((await call('POST', '/api/auth/refresh', { body: { refreshToken } })).status).toBe(200);
    const stale = (await signUp(call, 'second_user')).refreshToken;
    api.clock.advance(30 * DAY);
    const expired = await call('POST', '/api/auth/refresh', { body: { refreshToken: stale } });
    expect(expired.json).toMatchObject({ status: 401, message: '리프레시 토큰이 만료되었습니다. 다시 로그인해 주세요.' });
  });

  it('회전 → 유예 재발급 → 재사용 감지: 형제 토큰도 폐기되고 이전 액세스 토큰은 세션 해제', async () => {
    const { call } = api.build();
    const first = await signUp(call);
    api.clock.advance(2000);
    const a = await call('POST', '/api/auth/refresh', { body: { refreshToken: first.refreshToken } });
    api.clock.advance(1000);
    const sibling = await call('POST', '/api/auth/refresh', { body: { refreshToken: first.refreshToken } }); // 유예 안 1회
    expect(sibling.status).toBe(200);
    api.clock.advance(60_000); // 유예(30초) 경과 후 같은 토큰 재사용
    const reuse = await call('POST', '/api/auth/refresh', { body: { refreshToken: first.refreshToken } });
    expect(reuse.json.message).toContain('이미 사용된 리프레시 토큰');
    // 회전으로 받은 두 토큰 모두 죽었다
    for (const token of [a.json.refreshToken, sibling.json.refreshToken]) {
      expect((await call('POST', '/api/auth/refresh', { body: { refreshToken: token } })).status).toBe(401);
    }
    api.clock.advance(1000);
    expect((await call('GET', '/api/characters/me', { token: a.json.accessToken })).json.message).toBe(REVOKED);
    expect((await call('GET', '/api/characters/me', { token: first.accessToken })).json.message).toBe(REVOKED);
  });

  it('이 기기 로그아웃은 그 family 만 폐기하고 다른 기기 세션은 유지, 로그아웃 뒤 같은 토큰 재사용은 일반 401(재사용 감지 아님)', async () => {
    const { call } = api.build();
    const phone = await signUp(call);
    const tablet = (await call('POST', '/api/auth/login', { body: { username: 'tester_01', password: 'password123' } })).json;
    expect((await call('POST', '/api/auth/logout', { body: { refreshToken: phone.refreshToken } })).status).toBe(204);
    const afterLogout = await call('POST', '/api/auth/refresh', { body: { refreshToken: phone.refreshToken } });
    expect(afterLogout.json.message).toBe('리프레시 토큰이 유효하지 않습니다. 다시 로그인해 주세요.');
    expect((await call('POST', '/api/auth/refresh', { body: { refreshToken: tablet.refreshToken } })).status).toBe(200);
    // 재사용 감지가 아니므로 태블릿 액세스 토큰은 살아 있다
    expect((await call('GET', '/api/characters/me', { token: tablet.accessToken })).status).toBe(404);
  });

  it('모든 기기 로그아웃: 모든 family 와 이미 발급된 액세스 토큰이 무효, 새 로그인은 가능', async () => {
    const { call } = api.build();
    const phone = await signUp(call);
    const tablet = (await call('POST', '/api/auth/login', { body: { username: 'tester_01', password: 'password123' } })).json;
    api.clock.advance(2000);
    expect((await call('POST', '/api/auth/logout-all', { token: phone.accessToken })).status).toBe(204);
    for (const token of [phone.refreshToken, tablet.refreshToken]) {
      expect((await call('POST', '/api/auth/refresh', { body: { refreshToken: token } })).status).toBe(401);
    }
    for (const token of [phone.accessToken, tablet.accessToken]) {
      expect((await call('GET', '/api/characters/me', { token })).json.message).toBe(REVOKED);
    }
    api.clock.advance(1000);
    const again = await call('POST', '/api/auth/login', { body: { username: 'tester_01', password: 'password123' } });
    expect((await call('GET', '/api/characters/me', { token: again.json.accessToken })).status).toBe(404);
    const [live] = await api.db<{ n: number }[]>`select count(*)::int as n from refresh_token where revoked_at is null`;
    expect(live!.n).toBe(1);
  });

  it('로그아웃 상태 보관: 폐기 후 revoked-retention 이 지난 행은 정리 대상', async () => {
    const built = api.build();
    const session = await signUp(built.call);
    await built.call('POST', '/api/auth/logout', { body: { refreshToken: session.refreshToken } });
    expect(await built.services.refresh.purgeExpiredAndRevoked(api.db)).toBe(0);
    api.clock.advance(7 * DAY + 1000);
    expect(await built.services.refresh.purgeExpiredAndRevoked(api.db)).toBe(1);
  });

  it('웹 코드 교환(read scope): 조회는 되고 쓰기·Watch 발급·전체 로그아웃은 403, 리프레시 후에도 read 유지', async () => {
    const built = api.build();
    const { call } = built;
    // 같은 사용자(google:g-web)의 앱 로그인: 네이티브 구글 ID 토큰으로 전체 권한 세션과 데이터를 만든다
    const idToken = await new SignJWT({ name: '웹 사용자' })
      .setProtectedHeader({ alg: 'RS256', kid: google.kid })
      .setIssuer('https://accounts.google.com')
      .setAudience('ios-client.apps.googleusercontent.com')
      .setSubject('g-web')
      .setIssuedAt(T0 / 1000)
      .setExpirationTime(T0 / 1000 + 600)
      .sign(google.privateKey);
    const native = await call('POST', '/api/auth/google/native', { body: { idToken } });
    expect(native.json.userId).toBe('google:g-web');
    await call('POST', '/api/health-activities/sync', { token: native.json.accessToken, body: stepsBody(5000) });

    api.clock.advance(1000);
    const web = await call('POST', '/api/auth/google', {
      body: { code: 'code-1', redirectUri: 'http://localhost:5173/cb', codeVerifier: 'x'.repeat(43) },
    });
    expect(web.json).toMatchObject({ userId: 'google:g-web', expiresIn: 3600, refreshExpiresIn: 7 * 86400 });
    const t = web.json.accessToken;
    expect((await call('GET', '/api/characters/me', { token: t })).json.character.totalXp).toBe(25);
    expect((await call('GET', '/api/characters/google:g-web', { token: t })).status).toBe(200);
    expect((await call('GET', '/api/health-activities?date=2026-10-20', { token: t })).json.activities).toHaveLength(1);
    for (const [method, path, body] of [
      ['POST', '/api/health-activities/sync', stepsBody(9000)],
      ['POST', '/api/auth/watch-token', undefined],
      ['POST', '/api/auth/logout-all', undefined],
    ] as const) {
      const r = await call(method, path, { token: t, body });
      expect([r.status, r.json.message], path).toEqual([403, '조회 전용 토큰으로는 이 요청을 할 수 없습니다.']);
    }
    // 데이터는 바뀌지 않았다
    expect((await call('GET', '/api/characters/me', { token: native.json.accessToken })).json.character.totalXp).toBe(25);

    // 리프레시: read family 는 read 액세스 토큰을 다시 준다
    const rotated = await call('POST', '/api/auth/refresh', { body: { refreshToken: web.json.refreshToken } });
    expect((await call('POST', '/api/health-activities/sync', { token: rotated.json.accessToken, body: stepsBody(9000) })).status).toBe(403);

    // 앱의 이 기기 로그아웃(full family)은 같은 사용자의 조회 전용 토큰도 무효화한다(#103)
    api.clock.advance(1000);
    await call('POST', '/api/auth/logout', { body: { refreshToken: native.json.refreshToken } });
    expect((await call('GET', '/api/characters/me', { token: rotated.json.accessToken })).json.message).toBe(REVOKED);
    expect((await call('GET', '/api/characters/me', { token: t })).json.message).toBe(REVOKED);
    // 웹 로그인 리프레시 토큰으로 새로 받은 read 토큰은 기준 이후에 발급되므로 다시 쓸 수 있다
    api.clock.advance(2000);
    const fresh = await call('POST', '/api/auth/refresh', { body: { refreshToken: rotated.json.refreshToken } });
    expect((await call('GET', '/api/characters/me', { token: fresh.json.accessToken })).status).toBe(200);
  });

  it('Watch 토큰: 조회 전용 + 7일, 발급 이후 앱 로그아웃으로 무효', async () => {
    const { call } = api.build();
    const session = await signUp(call);
    const watch = await call('POST', '/api/auth/watch-token', { token: session.accessToken });
    expect(watch.json.expiresIn).toBe(7 * 86400);
    expect((await call('POST', '/api/health-activities/sync', { token: watch.json.accessToken, body: stepsBody(1000) })).status).toBe(403);
    expect((await call('GET', '/api/health-activities?date=2026-10-20', { token: watch.json.accessToken })).status).toBe(200);
  });
});

describeDb('Spring 호환(PostgreSQL)', () => {
  const api = useApi({ fetch: fakeFetch });

  const insertCredential = (username: string, hash: string, identifier: string) =>
    api.db`insert into user_credential (id, username, password_hash, user_identifier, display_name, created_at, updated_at)
           values (gen_random_uuid(), ${username}, ${hash}, ${identifier}, ${'스프링 사용자'}, now(), now())`;

  it('Spring BCryptPasswordEncoder($2a$10$)가 저장한 비밀번호로 HTTP 로그인된다(ASCII·한글·72바이트)', async () => {
    const { call } = api.build({ APP_RATE_LIMIT_LOGIN_PER_MINUTE: '0' });
    let i = 0;
    for (const [password, hash] of Object.entries(SPRING_BCRYPT)) {
      const username = `spring_user_${i++}`;
      await insertCredential(username, hash, `spring-id-${i}`);
      const ok = await call('POST', '/api/auth/login', { body: { username, password } });
      expect(ok.json).toMatchObject({ userId: `password:spring-id-${i}`, displayName: '스프링 사용자' });
      const bad = await call('POST', '/api/auth/login', { body: { username, password: `X${password.slice(1)}` } });
      expect(bad.status).toBe(400);
    }
  });

  it('Spring 이 발급한 액세스 토큰(full·read·legacy)을 HTTP 계층이 같은 규칙으로 받는다', async () => {
    const { call } = api.build();
    // full: 인증 통과 후 캐릭터가 없어 404
    expect((await call('GET', '/api/characters/me', { token: SPRING_FULL_TOKEN })).status).toBe(404);
    expect((await call('POST', '/api/health-activities/sync', { token: SPRING_FULL_TOKEN, body: stepsBody(1000) })).json.gainedXp).toBe(5);
    expect((await call('GET', '/api/characters/me', { token: SPRING_FULL_TOKEN })).json.userId).toBe('kakao:12345');
    // read: 조회만
    expect((await call('GET', '/api/characters/password:abc-123', { token: SPRING_READ_TOKEN })).status).toBe(404);
    expect((await call('POST', '/api/health-activities/sync', { token: SPRING_READ_TOKEN, body: stepsBody(1000) })).status).toBe(403);
    // legacy(iss·aud 없음)
    expect((await call('GET', '/api/characters/me', { token: SPRING_LEGACY_TOKEN })).status).toBe(404);
  });

  it('Spring 이 만든 user_auth_state(tokens_valid_after = EPOCH, read 기준 없음)는 기준 없음으로 해석한다', async () => {
    const { call } = api.build();
    await api.db`insert into user_auth_state (user_id, tokens_valid_after, read_tokens_valid_after) values ('kakao:12345', 'epoch'::timestamptz, null)`;
    expect((await call('GET', '/api/characters/me', { token: SPRING_FULL_TOKEN })).status).toBe(404);
  });

  it('Spring 이 기록한 무효화 기준(tokens_valid_after)보다 이른 iat 의 Spring 토큰은 거부', async () => {
    const { call } = api.build();
    await api.db`insert into user_auth_state (user_id, tokens_valid_after) values ('kakao:12345', ${new Date(T0 + 1000).toISOString()}::timestamptz)`;
    expect((await call('GET', '/api/characters/me', { token: SPRING_FULL_TOKEN })).json.message).toBe(REVOKED);
  });

  it('리프레시 토큰 저장 해시는 SHA-256 소문자 hex(독립 계산과 일치)이고, Spring 형식으로 직접 넣은 행도 회전된다', async () => {
    const { call } = api.build();
    const session = await signUp(call);
    const expected = createHash('sha256').update(session.refreshToken, 'utf8').digest('hex');
    const [row] = await api.db<{ token_hash: string; scope: string; grace_reissued: boolean }[]>`select token_hash, scope, grace_reissued from refresh_token`;
    expect(row).toEqual({ token_hash: expected, scope: 'full', grace_reissued: false });
    expect(session.refreshToken).toMatch(/^[A-Za-z0-9_-]{43}$/);

    // Spring 이 저장한 행(해시를 JDK 로 계산했다고 가정)을 Hono 가 회전시킨다
    const raw = 'spring-issued-refresh-token-0123456789ABCDEFG';
    const hash = createHash('sha256').update(raw, 'utf8').digest('hex');
    await api.db`insert into refresh_token (id, user_id, family_id, family_created_at, token_hash, issued_at, expires_at)
                 values (gen_random_uuid(), 'kakao:77', gen_random_uuid(), ${new Date(T0).toISOString()}::timestamptz,
                         ${hash}, ${new Date(T0).toISOString()}::timestamptz, ${new Date(T0 + 30 * DAY).toISOString()}::timestamptz)`;
    const rotated = await call('POST', '/api/auth/refresh', { body: { refreshToken: raw } });
    expect(rotated.json).toMatchObject({ userId: 'kakao:77', tokenType: 'Bearer', expiresIn: 3600 });
    const [legacyRow] = await api.db`select scope, revoked_at is not null as revoked from refresh_token where token_hash = ${hash}`;
    expect(legacyRow).toEqual({ scope: 'full', revoked: true }); // 기본값 'full'(Spring V5 이전 행)
  });

  it('가입한 비밀번호 해시는 Spring 이 검증할 수 있는 $2a/$2b + cost 10 형식, user_credential 컬럼 제약 안', async () => {
    const { call } = api.build();
    await signUp(call, 'compat_user');
    const [row] = await api.db<{ password_hash: string; username: string; display_name: string }[]>`select password_hash, username, display_name from user_credential`;
    expect(row!.password_hash).toMatch(/^\$2[ab]\$10\$[./A-Za-z0-9]{53}$/);
    expect(row!.password_hash.length).toBeLessThanOrEqual(100);
    expect(row).toMatchObject({ username: 'compat_user', display_name: '테스터' });
  });
});
