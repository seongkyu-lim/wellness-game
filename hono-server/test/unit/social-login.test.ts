import { describe, expect, it } from 'vitest';
import { HttpClient, HttpStatusError } from '../../src/auth/http-client.js';
import { createServices } from '../../src/services.js';
import { quietLogs, testConfig, T0 } from '../support/fixtures.js';
import { apiError, json, stubFetch } from '../support/oauth.js';

const KAKAO_INFO = 'https://kapi.kakao.com/v1/user/access_token_info';
const KAKAO_ME = 'https://kapi.kakao.com/v2/user/me';
const NAVER_TOKEN = 'https://nid.naver.com/oauth2.0/token';
const NAVER_ME = 'https://openapi.naver.com/v1/nid/me';
const GOOGLE_TOKEN = 'https://oauth2.googleapis.com/token';
const GOOGLE_ME = 'https://openidconnect.googleapis.com/v1/userinfo';

function services(routes: Parameters<typeof stubFetch>[0], env: Record<string, string> = {}) {
  const stub = stubFetch(routes);
  return { ...stub, s: createServices(testConfig(env), { clock: () => T0, fetch: stub.fn }) };
}

describe('카카오 네이티브 로그인', () => {
  quietLogs();
  const ok = {
    [KAKAO_INFO]: json({ id: 777, expires_in: 3000, app_id: 12345 }),
    [KAKAO_ME]: json({ id: 777, properties: { nickname: '카카오닉' } }),
  };

  it('토큰 정보(app_id)와 프로필 id 가 일치하면 kakao:{회원번호} 와 닉네임을 돌려준다', async () => {
    const { s, calls } = services(ok);
    await expect(s.kakao.authenticate('kakao-access')).resolves.toEqual({ userId: 'kakao:777', displayName: '카카오닉' });
    expect(calls.map((c) => c.url)).toEqual([KAKAO_INFO, KAKAO_ME]);
    // 사용자 토큰은 Authorization 헤더로만 보낸다(URL 에 싣지 않음)
    expect(calls.every((c) => c.headers.Authorization === 'Bearer kakao-access' && !c.url.includes('kakao-access'))).toBe(true);
  });

  it('닉네임이 없으면 displayName 은 null', async () => {
    const { s } = services({ ...ok, [KAKAO_ME]: json({ id: 777 }) });
    await expect(s.kakao.authenticate('t')).resolves.toEqual({ userId: 'kakao:777', displayName: null });
  });

  it('네이티브 앱 ID 가 설정되지 않았으면 503 이고 외부 호출을 하지 않는다', async () => {
    const { s, calls } = services(ok, { OAUTH_KAKAO_NATIVE_APP_ID: '' });
    await expect(s.kakao.authenticate('t')).rejects.toMatchObject(apiError(503, 'auth.kakao.unavailable'));
    expect(calls).toHaveLength(0);
  });

  it('빈 토큰은 400', async () => {
    const { s } = services(ok);
    await expect(s.kakao.authenticate('   ')).rejects.toMatchObject(apiError(400, 'auth.native.access-token-blank'));
  });

  it('다른 앱이 발급한 토큰(app_id 불일치)은 401 wrong-app, 프로필은 조회하지 않는다', async () => {
    const { s, calls } = services({ ...ok, [KAKAO_INFO]: json({ id: 777, expires_in: 3000, app_id: 99999 }) });
    await expect(s.kakao.authenticate('t')).rejects.toMatchObject(apiError(401, 'auth.kakao.wrong-app'));
    expect(calls.map((c) => c.url)).toEqual([KAKAO_INFO]);
  });

  it.each([
    ['회원번호 0', { id: 0, expires_in: 100, app_id: '12345' }],
    ['만료(expires_in 0)', { id: 777, expires_in: 0, app_id: '12345' }],
    ['id 가 숫자가 아님', { id: 'abc', expires_in: 100, app_id: '12345' }],
    ['2^53 이상 회원번호', { id: 2 ** 53, expires_in: 100, app_id: '12345' }],
  ])('토큰 정보가 무효(%s)면 401', async (_name, info) => {
    const { s } = services({ ...ok, [KAKAO_INFO]: json(info) });
    await expect(s.kakao.authenticate('t')).rejects.toMatchObject(apiError(401, 'auth.kakao.access-token-invalid'));
  });

  it('토큰 정보 본문이 비어 있어도 401(NPE 없이)', async () => {
    const { s } = services({ ...ok, [KAKAO_INFO]: new Response('', { status: 200 }) });
    await expect(s.kakao.authenticate('t')).rejects.toMatchObject(apiError(401, 'auth.kakao.access-token-invalid'));
  });

  it('프로필 id 가 토큰 정보의 id 와 다르면 401', async () => {
    const { s } = services({ ...ok, [KAKAO_ME]: json({ id: 778 }) });
    await expect(s.kakao.authenticate('t')).rejects.toMatchObject(apiError(401, 'auth.kakao.access-token-invalid'));
  });

  it.each([
    [401, 401, 'auth.kakao.access-token-invalid'],
    [400, 401, 'auth.kakao.access-token-invalid'],
    [429, 503, 'auth.kakao.verification-failed'],
    [500, 503, 'auth.kakao.verification-failed'],
  ])('카카오가 HTTP %i 로 응답하면 %i %s', async (upstream, status, code) => {
    const { s } = services({ ...ok, [KAKAO_INFO]: new Response('{"code":-401}', { status: upstream }) });
    await expect(s.kakao.authenticate('t')).rejects.toMatchObject(apiError(status, code));
  });

  it('연결 실패·깨진 JSON 은 503', async () => {
    const down = services({ ...ok, [KAKAO_INFO]: new Error('ECONNRESET') });
    await expect(down.s.kakao.authenticate('t')).rejects.toMatchObject(apiError(503, 'auth.kakao.verification-failed'));
    const broken = services({ ...ok, [KAKAO_INFO]: new Response('<html>', { status: 200 }) });
    await expect(broken.s.kakao.authenticate('t')).rejects.toMatchObject(apiError(503, 'auth.kakao.verification-failed'));
  });
});

describe('네이버 네이티브 로그인', () => {
  quietLogs();
  const ok = {
    [NAVER_TOKEN]: json({ access_token: 'refreshed-access', token_type: 'bearer' }),
    [NAVER_ME]: json({ resultcode: '00', response: { id: 'naver-id', name: '네이버이름', nickname: '별명' } }),
  };

  it('refresh token 을 서버 client_id·secret 으로 갱신하고, 갱신된 access token 으로만 프로필을 조회한다', async () => {
    const { s, calls } = services(ok);
    await expect(s.naver.authenticate('client-supplied-access', ' naver-refresh ')).resolves.toEqual({
      userId: 'naver:naver-id',
      displayName: '네이버이름',
    });
    expect(Object.fromEntries(new URLSearchParams(calls[0]!.body))).toEqual({
      grant_type: 'refresh_token',
      client_id: 'naver-client',
      client_secret: 'naver-secret',
      refresh_token: 'naver-refresh',
    });
    expect(calls[1]!.headers.Authorization).toBe('Bearer refreshed-access');
    expect(calls.some((c) => c.headers.Authorization === 'Bearer client-supplied-access')).toBe(false);
  });

  it('이름이 없으면 닉네임, 둘 다 없으면 null', async () => {
    const nick = services({ ...ok, [NAVER_ME]: json({ resultcode: '00', response: { id: 'x', nickname: '별명' } }) });
    await expect(nick.s.naver.authenticate('a', 'r')).resolves.toMatchObject({ displayName: '별명' });
    const none = services({ ...ok, [NAVER_ME]: json({ resultcode: '00', response: { id: 'x' } }) });
    await expect(none.s.naver.authenticate('a', 'r')).resolves.toMatchObject({ displayName: null });
  });

  it('비밀 설정이 없으면 503', async () => {
    const { s } = services(ok, { OAUTH_NAVER_CLIENT_SECRET: '' });
    await expect(s.naver.authenticate('a', 'r')).rejects.toMatchObject(apiError(503, 'auth.naver.unavailable'));
  });

  it('빈 accessToken·refreshToken 은 400', async () => {
    const { s } = services(ok);
    await expect(s.naver.authenticate(' ', 'r')).rejects.toMatchObject(apiError(400, 'auth.native.access-token-blank'));
    await expect(s.naver.authenticate('a', '')).rejects.toMatchObject(apiError(400, 'auth.native.refresh-token-blank'));
  });

  it('네이버가 200 + {"error"} 로 거절하거나 access_token 이 없으면 401', async () => {
    const withError = services({ ...ok, [NAVER_TOKEN]: json({ error: 'invalid_request', error_description: 'x' }) });
    await expect(withError.s.naver.authenticate('a', 'r')).rejects.toMatchObject(apiError(401, 'auth.naver.access-token-invalid'));
    const noToken = services({ ...ok, [NAVER_TOKEN]: json({ access_token: '  ' }) });
    await expect(noToken.s.naver.authenticate('a', 'r')).rejects.toMatchObject(apiError(401, 'auth.naver.access-token-invalid'));
  });

  it('프로필 resultcode 가 00 이 아니거나 id 가 비어 있으면 401', async () => {
    const bad = services({ ...ok, [NAVER_ME]: json({ resultcode: '024', response: {} }) });
    await expect(bad.s.naver.authenticate('a', 'r')).rejects.toMatchObject(apiError(401, 'auth.naver.access-token-invalid'));
    const noId = services({ ...ok, [NAVER_ME]: json({ resultcode: '00', response: { name: '이름' } }) });
    await expect(noId.s.naver.authenticate('a', 'r')).rejects.toMatchObject(apiError(401, 'auth.social.failed'));
  });

  it('4xx 는 401, 5xx·연결 실패는 503', async () => {
    const c4 = services({ ...ok, [NAVER_TOKEN]: new Response('', { status: 403 }) });
    await expect(c4.s.naver.authenticate('a', 'r')).rejects.toMatchObject(apiError(401, 'auth.naver.access-token-invalid'));
    const c5 = services({ ...ok, [NAVER_TOKEN]: new Response('', { status: 502 }) });
    await expect(c5.s.naver.authenticate('a', 'r')).rejects.toMatchObject(apiError(503, 'auth.naver.verification-failed'));
    const net = services({ ...ok, [NAVER_ME]: new Error('boom') });
    await expect(net.s.naver.authenticate('a', 'r')).rejects.toMatchObject(apiError(503, 'auth.naver.verification-failed'));
  });
});

describe('웹 코드 교환(SocialAuthService)', () => {
  quietLogs();
  const verifier = 'v'.repeat(43);
  const ok = {
    [GOOGLE_TOKEN]: json({ access_token: 'g-access' }),
    [GOOGLE_ME]: json({ sub: 'g-1', name: '구글' }),
    'https://kauth.kakao.com/oauth/token': json({ access_token: 'k-access' }),
    [KAKAO_ME]: json({ id: 5, properties: { nickname: 'k' } }),
    [NAVER_TOKEN]: json({ access_token: 'n-access' }),
    [NAVER_ME]: json({ resultcode: '00', response: { id: 'n1' } }),
  };

  it('지원하지 않는 공급자는 400', async () => {
    const { s } = services(ok);
    await expect(s.social.authenticate('facebook', 'c', 'http://r', verifier)).rejects.toMatchObject({
      ...apiError(400, 'auth.social.unsupported-provider'),
      localized: { code: 'auth.social.unsupported-provider', args: ['facebook'] },
    });
  });

  it('client id 가 없으면 503(공급자 이름을 응답에 넣지 않음)', async () => {
    const { s, calls } = services(ok, { OAUTH_KAKAO_CLIENT_ID: '  ' });
    await expect(s.social.authenticate('kakao', 'c', 'http://r', verifier)).rejects.toMatchObject(apiError(503, 'auth.social.not-configured'));
    expect(calls).toHaveLength(0);
  });

  it('구글·카카오는 PKCE code_verifier 필수, 네이버는 필요 없고 보내지도 않는다', async () => {
    const { s, calls } = services(ok);
    await expect(s.social.authenticate('google', 'c', 'http://r', null)).rejects.toMatchObject(
      apiError(400, 'auth.social.code-verifier-required'),
    );
    await expect(s.social.authenticate('kakao', 'c', 'http://r', '  ')).rejects.toMatchObject(
      apiError(400, 'auth.social.code-verifier-required'),
    );
    expect(calls).toHaveLength(0);
    await expect(s.social.authenticate('naver', 'c', 'http://r', verifier)).resolves.toEqual({ userId: 'naver:n1', displayName: null });
    expect(new URLSearchParams(calls[0]!.body).has('code_verifier')).toBe(false);
  });

  it('토큰 교환 폼: client_secret 은 설정된 공급자에만, 카카오는 비어 있으면 생략', async () => {
    const { s, calls } = services(ok);
    await s.social.authenticate('kakao', 'code-k', 'http://r/k', verifier);
    const form = Object.fromEntries(new URLSearchParams(calls[0]!.body));
    expect(form).toEqual({
      grant_type: 'authorization_code',
      client_id: 'kakao-client',
      redirect_uri: 'http://r/k',
      code: 'code-k',
      code_verifier: verifier,
    });
    expect(calls[1]!.headers.Authorization).toBe('Bearer k-access');
  });

  it('토큰 응답에 access_token 이 없으면 503 token-issue-failed', async () => {
    const { s } = services({ ...ok, [GOOGLE_TOKEN]: json({ error: 'invalid_grant' }) });
    await expect(s.social.authenticate('google', 'c', 'http://r', verifier)).rejects.toMatchObject(
      apiError(503, 'auth.social.token-issue-failed'),
    );
  });

  it('공급자 4xx·연결 실패는 503 로 바꾸고 공급자 이름을 문구 인자로 넘긴다', async () => {
    const rejected = services({ ...ok, [GOOGLE_TOKEN]: new Response('{"error":"invalid_grant"}', { status: 400 }) });
    await expect(rejected.s.social.authenticate('google', 'c', 'http://r', verifier)).rejects.toMatchObject({
      status: 503,
      localized: { code: 'auth.social.failed', args: ['google'] },
      cause: expect.any(HttpStatusError),
    });
    const down = services({ ...ok, [GOOGLE_ME]: new Error('dns') });
    await expect(down.s.social.authenticate('google', 'c', 'http://r', verifier)).rejects.toMatchObject(apiError(503, 'auth.social.failed'));
  });

  it('프로필에 식별자가 없으면 공용 userId("kakao:0")가 생기지 않도록 401', async () => {
    const { s } = services({ ...ok, [KAKAO_ME]: json({ id: 0 }) });
    await expect(s.social.authenticate('kakao', 'c', 'http://r', verifier)).rejects.toMatchObject(apiError(401, 'auth.social.failed'));
    const g = services({ ...ok, [GOOGLE_ME]: json({ sub: '   ' }) });
    await expect(g.s.social.authenticate('google', 'c', 'http://r', verifier)).rejects.toMatchObject(apiError(401, 'auth.social.failed'));
  });
});

describe('HttpClient', () => {
  it('제한 시간 안에 응답이 없으면 전송 오류로 끊는다(실제 타이머 20ms, fetch 는 abort 를 기다림)', async () => {
    const hang = (_url: string, init?: RequestInit) =>
      new Promise<Response>((_resolve, reject) => init?.signal?.addEventListener('abort', () => reject(init.signal!.reason)));
    const client = new HttpClient(hang, 20);
    await expect(client.getJson('https://example.test')).rejects.toMatchObject({ name: 'HttpTransportError' });
  });

  it('빈 본문은 null, 4xx 는 isClientError, 5xx 는 아님', async () => {
    const client = (status: number, body = '') => new HttpClient(async () => new Response(body, { status }), 1000);
    await expect(client(200).getJson('u')).resolves.toBeNull();
    await expect(client(404).getJson('u')).rejects.toMatchObject({ status: 404, isClientError: true });
    await expect(client(503).postForm('u', {})).rejects.toMatchObject({ status: 503, isClientError: false });
    await expect(client(200, 'text').getText('u')).resolves.toBe('text');
  });
});
