import { beforeAll, expect, it } from 'vitest';
import worker from '../../src/index.js';
import { sha256Hex } from '../../src/lib/crypto.js';
import { describeDb, DATABASE_URL_TEST } from '../support/api.js';
import { T0, TEST_SECRET } from '../support/fixtures.js';
import { signUp, stepsBody, useApi } from '../support/flow.js';
import { json, jwksOf, makeKey, signIdToken, stubFetch, type TestKey } from '../support/oauth.js';

let apple: TestKey;
beforeAll(async () => {
  apple = await makeKey('apple-kid');
});

/** 소셜 공급자(카카오·네이버·애플)를 가짜로 둔 HTTP 경로: 검증 → 세션 발급 → 같은 사용자 데이터 */
describeDb('소셜 로그인 HTTP 경로(PostgreSQL)', () => {
  let current: ReturnType<typeof stubFetch>;
  const api = useApi({ fetch: () => current.fn, env: { APP_RATE_LIMIT_AUTH_PER_MINUTE: '0' } });
  const setup = (routes: Parameters<typeof stubFetch>[0] = {}) => {
    current = stubFetch({
      'https://kapi.kakao.com/v1/user/access_token_info': json({ id: 4242, expires_in: 100, app_id: '12345' }),
      'https://kapi.kakao.com/v2/user/me': json({ id: 4242, properties: { nickname: '카카오' } }),
      'https://nid.naver.com/oauth2.0/token': json({ access_token: 'n-fresh' }),
      'https://openapi.naver.com/v1/nid/me': json({ resultcode: '00', response: { id: 'nv-1', name: '네이버' } }),
      'https://appleid.apple.com/auth/keys': json(jwksOf(apple)),
      ...routes,
    });
    return api.build();
  };

  it('카카오 네이티브: 세션 발급, 같은 회원은 같은 userId 로 데이터를 이어 쓴다', async () => {
    const { call } = setup();
    const first = await call('POST', '/api/auth/kakao/native', { body: { accessToken: 'kakao-token' } });
    expect(first.json).toMatchObject({ userId: 'kakao:4242', displayName: '카카오', tokenType: 'Bearer', refreshExpiresIn: 30 * 86400 });
    await call('POST', '/api/health-activities/sync', { token: first.json.accessToken, body: stepsBody(3000) });
    const second = await call('POST', '/api/auth/kakao/native', { body: { accessToken: 'another-token' } });
    expect((await call('GET', '/api/characters/me', { token: second.json.accessToken })).json.character.totalXp).toBe(15);
    // 전체 권한 세션: 리프레시 family 의 scope 는 full
    const scopes = await api.db`select distinct scope from refresh_token`;
    expect(scopes.map((r) => r.scope)).toEqual(['full']);
  });

  it('카카오 네이티브 검증 실패는 세션을 만들지 않는다(401·503, refresh_token 행 없음)', async () => {
    const { call } = setup({ 'https://kapi.kakao.com/v1/user/access_token_info': json({ id: 4242, expires_in: 100, app_id: 'other-app' }) });
    expect((await call('POST', '/api/auth/kakao/native', { body: { accessToken: 'x' } })).json.message).toBe('이 앱에 발급된 카카오 토큰이 아닙니다.');
    const down = setup({ 'https://kapi.kakao.com/v1/user/access_token_info': new Response('', { status: 500 }) });
    expect((await down.call('POST', '/api/auth/kakao/native', { body: { accessToken: 'x' } })).status).toBe(503);
    expect(await api.db`select 1 from refresh_token`).toHaveLength(0);
  });

  it('네이버 네이티브: 갱신된 토큰으로 프로필 조회, 잘못된 본문은 400', async () => {
    const { call } = setup();
    const ok = await call('POST', '/api/auth/naver/native', { body: { accessToken: 'a', refreshToken: 'r' } });
    expect(ok.json).toMatchObject({ userId: 'naver:nv-1', displayName: '네이버' });
    expect(current.calls[0]!.url).toBe('https://nid.naver.com/oauth2.0/token');
    expect((await call('POST', '/api/auth/naver/native', { body: { accessToken: 'a' } })).json.message).toBe('refreshToken: 비어 있을 수 없습니다.');
    expect((await call('POST', '/api/auth/naver/native', { body: { accessToken: 'x'.repeat(4097), refreshToken: 'r' } })).json.message).toMatch(/^accessToken: /);
  });

  it('애플 네이티브: nonce 검증, 이름은 최초 로그인에만 오므로 요청의 fullName 을 쓴다', async () => {
    const { call } = setup();
    const token = await signIdToken(apple, {
      iat: T0 / 1000,
      iss: 'https://appleid.apple.com',
      aud: 'com.example.wellness',
      sub: 'apple-001',
      claims: { nonce: await sha256Hex('raw-nonce') },
    });
    const bad = await call('POST', '/api/auth/apple/native', { body: { identityToken: token, nonce: 'wrong' } });
    expect(bad.json.message).toBe('애플 identity token의 nonce가 일치하지 않습니다.');
    const ok = await call('POST', '/api/auth/apple/native', { body: { identityToken: token, nonce: 'raw-nonce', fullName: ' 홍 길동 ' } });
    expect(ok.json).toMatchObject({ userId: 'apple:apple-001', displayName: '홍 길동' });
    const en = await call('POST', '/api/auth/apple/native', { body: { identityToken: token, nonce: 'raw-nonce' }, headers: { 'Accept-Language': 'en' } });
    expect(en.json.displayName).toBe('Apple user');
    expect((await call('POST', '/api/auth/apple/native', { body: { identityToken: token, nonce: 'n'.repeat(257) } })).json.message).toMatch(/^nonce: /);
  });

  it('웹 코드 교환(네이버는 PKCE 없음)은 조회 전용 세션이고, 지원하지 않는 공급자 경로는 인증부터 요구한다', async () => {
    const { call } = setup();
    const naverWeb = await call('POST', '/api/auth/naver', { body: { code: 'c', redirectUri: 'http://localhost:5173/cb' } });
    expect(naverWeb.json).toMatchObject({ userId: 'naver:nv-1', expiresIn: 3600, refreshExpiresIn: 7 * 86400 });
    const [row] = await api.db`select scope from refresh_token`;
    expect(row!.scope).toBe('read');
    const github = await call('POST', '/api/auth/github', { body: { code: 'c', redirectUri: 'http://r' } });
    expect(github.status).toBe(401); // 공개 경로가 아니라 인증부터 요구한다
  });

  it('소셜 userId 와 아이디/비밀번호 userId 는 접두사로 구분돼 서로의 데이터를 볼 수 없다', async () => {
    const { call } = setup();
    const password = await signUp(call);
    const kakao = await call('POST', '/api/auth/kakao/native', { body: { accessToken: 'kakao-token' } });
    expect((await call('GET', `/api/characters/${kakao.json.userId}`, { token: password.accessToken })).status).toBe(403);
  });
});

describeDb('Workers scheduled() 정리(PostgreSQL)', () => {
  const api = useApi();

  it('만료·오래전 폐기된 리프레시 토큰 행만 지운다', async () => {
    const insert = (user: string, hash: string, expires: string, revoked: string | null) =>
      api.db`insert into refresh_token (id, user_id, family_id, family_created_at, token_hash, issued_at, expires_at, revoked_at)
             values (gen_random_uuid(), ${user}, gen_random_uuid(), now(), ${hash}, now(), ${expires}::timestamptz, ${revoked}::timestamptz)`;
    await insert('u-expired', 'h1', '2020-01-01T00:00:00Z', null);
    await insert('u-old-revoked', 'h2', '2999-01-01T00:00:00Z', '2020-01-01T00:00:00Z');
    await insert('u-live', 'h3', '2999-01-01T00:00:00Z', null);
    await insert('u-recent-revoked', 'h4', '2999-01-01T00:00:00Z', new Date().toISOString());
    const pending: Promise<unknown>[] = [];
    const ctx = { waitUntil: (p: Promise<unknown>) => pending.push(p), passThroughOnException: () => {} } as unknown as Parameters<typeof worker.scheduled>[2];
    await worker.scheduled({ cron: '0 19 * * *', scheduledTime: Date.now() }, { AUTH_JWT_SECRET: TEST_SECRET, DATABASE_URL: DATABASE_URL_TEST }, ctx);
    await Promise.all(pending);
    const left = await api.db<{ user_id: string }[]>`select user_id from refresh_token order by user_id`;
    expect(left.map((r) => r.user_id)).toEqual(['u-live', 'u-recent-revoked']);
  });
});
