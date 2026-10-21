import { SignJWT } from 'jose';
import { beforeAll, describe, expect, it } from 'vitest';
import { sha256Hex } from '../../src/lib/crypto.js';
import { createServices, type Services } from '../../src/services.js';
import { quietLogs, T0, testConfig } from '../support/fixtures.js';
import { apiError, json, jwksOf, makeKey, signIdToken, stubFetch, type TestKey } from '../support/oauth.js';

const GOOGLE_JWKS = 'https://www.googleapis.com/oauth2/v3/certs';
const APPLE_JWKS = 'https://appleid.apple.com/auth/keys';
const IAT = T0 / 1000;
const BUNDLE = 'com.example.wellness';

let key: TestKey;
let otherKey: TestKey;
beforeAll(async () => {
  key = await makeKey('kid-1');
  otherKey = await makeKey('kid-1'); // 같은 kid 의 다른 키(위조)
});

function build(env: Record<string, string> = {}, jwks: unknown = jwksOf(key)) {
  const stub = stubFetch({ [GOOGLE_JWKS]: json(jwks), [APPLE_JWKS]: json(jwks) });
  const s: Services = createServices(testConfig(env), { clock: () => T0, fetch: stub.fn });
  return { s, calls: stub.calls };
}

describe('구글 ID 토큰(네이티브)', () => {
  quietLogs();
  const IOS = 'ios-client.apps.googleusercontent.com';
  const google = (options: Partial<Parameters<typeof signIdToken>[1]> = {}, signer = key) =>
    signIdToken(signer, { iat: IAT, iss: 'https://accounts.google.com', aud: IOS, sub: 'g-sub', claims: { name: '홍길동' }, ...options });

  it('정상 토큰: google:{sub} 와 이름, 공백이 섞인 토큰도 trim', async () => {
    const { s } = build();
    await expect(s.google.authenticate(` ${await google()} `)).resolves.toEqual({ userId: 'google:g-sub', displayName: '홍길동' });
  });

  it('iss 는 accounts.google.com 도 허용하고 이름이 없으면 null', async () => {
    const { s } = build();
    await expect(s.google.authenticate(await google({ iss: 'accounts.google.com', claims: {} }))).resolves.toEqual({
      userId: 'google:g-sub',
      displayName: null,
    });
  });

  it('aud 가 배열이어도 전부 허용 목록 안이어야 한다(하나라도 밖이면 거부)', async () => {
    const { s } = build();
    const both = ['ios-client.apps.googleusercontent.com', 'android-server.apps.googleusercontent.com'];
    await expect(s.google.authenticate(await google({ aud: both }))).resolves.toMatchObject({ userId: 'google:g-sub' });
    await expect(s.google.authenticate(await google({ aud: [IOS, 'evil.apps.googleusercontent.com'] }))).rejects.toMatchObject(
      apiError(401, 'auth.google.wrong-audience'),
    );
  });

  it('웹 클라이언트 ID(aud)·aud 없음·허용 밖 azp 는 wrong-audience', async () => {
    const { s } = build();
    for (const token of [
      await google({ aud: 'web-client.apps.googleusercontent.com' }),
      await google({ aud: null }),
      await google({ claims: { azp: 'web-client.apps.googleusercontent.com' } }),
    ]) {
      await expect(s.google.authenticate(token)).rejects.toMatchObject(apiError(401, 'auth.google.wrong-audience'));
    }
    // 허용된 azp 는 통과
    await expect(s.google.authenticate(await google({ claims: { azp: IOS } }))).resolves.toBeTruthy();
  });

  it('iss 가 다르거나 없으면 wrong-issuer', async () => {
    const { s } = build();
    await expect(s.google.authenticate(await google({ iss: 'https://evil.example.com' }))).rejects.toMatchObject(
      apiError(401, 'auth.google.wrong-issuer'),
    );
    await expect(s.google.authenticate(await google({ iss: null }))).rejects.toMatchObject(apiError(401, 'auth.google.wrong-issuer'));
  });

  it('exp 가 없거나 sub 가 비면 invalid, exp 가 지났으면 expired', async () => {
    const { s } = build();
    await expect(s.google.authenticate(await google({ exp: null }))).rejects.toMatchObject(apiError(401, 'auth.google.id-token-invalid'));
    await expect(s.google.authenticate(await google({ sub: '  ' }))).rejects.toMatchObject(apiError(401, 'auth.google.id-token-invalid'));
    await expect(s.google.authenticate(await google({ sub: null }))).rejects.toMatchObject(apiError(401, 'auth.google.id-token-invalid'));
    await expect(s.google.authenticate(await google({ iat: IAT - 1200, exp: IAT - 600 }))).rejects.toMatchObject(
      apiError(401, 'auth.google.id-token-expired'),
    );
  });

  it('주입한 시계로 만료를 판단한다(발급 시각 + 600초 직전엔 통과, 정각엔 만료)', async () => {
    let now = T0;
    const stub = stubFetch({ [GOOGLE_JWKS]: json(jwksOf(key)) });
    const s = createServices(testConfig(), { clock: () => now, fetch: stub.fn });
    const token = await google({ exp: IAT + 600 });
    now = T0 + 599_000;
    await expect(s.google.authenticate(token)).resolves.toBeTruthy();
    now = T0 + 600_000;
    await expect(s.google.authenticate(token)).rejects.toMatchObject(apiError(401, 'auth.google.id-token-expired'));
  });

  it('서명이 다르면(같은 kid 의 위조 키)·HS256·none·모르는 kid·kid 없음·깨진 토큰은 invalid', async () => {
    const { s } = build();
    const forged = await google({}, otherKey);
    const hs256 = await new SignJWT({}).setProtectedHeader({ alg: 'HS256', kid: 'kid-1' }).setSubject('x').setAudience(IOS).sign(new Uint8Array(32).fill(7));
    const none = `${Buffer.from('{"alg":"none","kid":"kid-1"}').toString('base64url')}.${Buffer.from(`{"sub":"x","aud":"${IOS}"}`).toString('base64url')}.`;
    const noKid = await new SignJWT({}).setProtectedHeader({ alg: 'RS256' }).setSubject('x').setAudience(IOS).sign(key.privateKey);
    for (const token of [forged, hs256, none, await google({ kid: 'nope' }), noKid, 'not.a.jwt', 'garbage']) {
      await expect(s.google.authenticate(token)).rejects.toMatchObject(apiError(401, 'auth.google.id-token-invalid'));
    }
  });

  it('빈 토큰은 400, 네이티브 클라이언트 ID 미설정은 503', async () => {
    await expect(build().s.google.authenticate('  ')).rejects.toMatchObject(apiError(400, 'auth.google.id-token-blank'));
    const { s, calls } = build({ OAUTH_GOOGLE_NATIVE_CLIENT_IDS: '' });
    await expect(s.google.authenticate(await google())).rejects.toMatchObject(apiError(503, 'auth.google.unavailable'));
    expect(calls).toHaveLength(0);
  });

  it('JWKS 를 받지 못하면(키 캐시 없음) 503', async () => {
    const stub = stubFetch({ [GOOGLE_JWKS]: new Response('', { status: 500 }) });
    const s = createServices(testConfig(), { clock: () => T0, fetch: stub.fn });
    await expect(s.google.authenticate(await google())).rejects.toMatchObject(apiError(503, 'auth.google.verification-failed'));
  });
});

describe('애플 identity token(네이티브)', () => {
  quietLogs();
  const apple = (options: Partial<Parameters<typeof signIdToken>[1]> = {}, signer = key) =>
    signIdToken(signer, { iat: IAT, iss: 'https://appleid.apple.com', aud: BUNDLE, sub: 'apple-sub', ...options });

  it('정상 토큰: apple:{sub}, 이름은 요청의 fullName(trim)', async () => {
    const { s } = build();
    await expect(s.apple.authenticate(await apple(), '  김 철수 ', null, 'ko')).resolves.toEqual({
      userId: 'apple:apple-sub',
      displayName: '김 철수',
    });
  });

  it('이름이 없으면 로케일별 기본 이름', async () => {
    const { s } = build();
    const token = await apple();
    await expect(s.apple.authenticate(token, null, null, 'ko')).resolves.toMatchObject({ displayName: 'Apple 사용자' });
    await expect(s.apple.authenticate(token, '   ', null, 'en')).resolves.toMatchObject({ displayName: 'Apple user' });
  });

  it('이름은 코드 포인트 100자에서 자른다(이모지를 반으로 쪼개지 않음)', async () => {
    const { s } = build();
    const long = '😀'.repeat(150);
    const result = await s.apple.authenticate(await apple(), long, null, 'ko');
    expect(Array.from(result.displayName!)).toHaveLength(100);
    expect(result.displayName).toBe('😀'.repeat(100));
    const exact = await s.apple.authenticate(await apple(), 'a'.repeat(100), null, 'ko');
    expect(exact.displayName).toHaveLength(100);
  });

  it('aud 는 번들 ID 를 포함해야 하고(배열 가능), iss 는 정확히 일치', async () => {
    const { s } = build();
    await expect(s.apple.authenticate(await apple({ aud: ['other', BUNDLE] }), null, null, 'ko')).resolves.toBeTruthy();
    await expect(s.apple.authenticate(await apple({ aud: 'com.other.app' }), null, null, 'ko')).rejects.toMatchObject(
      apiError(401, 'auth.apple.wrong-audience'),
    );
    await expect(s.apple.authenticate(await apple({ aud: null }), null, null, 'ko')).rejects.toMatchObject(
      apiError(401, 'auth.apple.wrong-audience'),
    );
    await expect(s.apple.authenticate(await apple({ iss: 'https://appleid.apple.com/' }), null, null, 'ko')).rejects.toMatchObject(
      apiError(401, 'auth.apple.wrong-issuer'),
    );
  });

  it('issuer 검사가 audience 검사보다 먼저다(둘 다 틀리면 wrong-issuer)', async () => {
    const { s } = build();
    await expect(s.apple.authenticate(await apple({ iss: 'x', aud: 'y' }), null, null, 'ko')).rejects.toMatchObject(
      apiError(401, 'auth.apple.wrong-issuer'),
    );
  });

  it('nonce: 원문이 오면 토큰의 nonce 가 SHA-256 hex 와 같아야 한다', async () => {
    const { s } = build();
    const hashed = await sha256Hex('raw-nonce-123');
    const good = await apple({ claims: { nonce: hashed } });
    await expect(s.apple.authenticate(good, null, 'raw-nonce-123', 'ko')).resolves.toBeTruthy();
    await expect(s.apple.authenticate(good, null, 'other-nonce', 'ko')).rejects.toMatchObject(apiError(401, 'auth.apple.nonce-mismatch'));
    // 토큰에 nonce 가 없는데 원문을 보내도 불일치
    await expect(s.apple.authenticate(await apple(), null, 'raw-nonce-123', 'ko')).rejects.toMatchObject(
      apiError(401, 'auth.apple.nonce-mismatch'),
    );
    // 해시 자체를 원문으로 보내면 거부(이중 해시가 아니므로)
    await expect(s.apple.authenticate(good, null, hashed, 'ko')).rejects.toMatchObject(apiError(401, 'auth.apple.nonce-mismatch'));
  });

  it('nonce 를 보내지 않으면 통과, OAUTH_APPLE_REQUIRE_NONCE=true 면 빈 문자열도 거부', async () => {
    const lenient = build();
    await expect(lenient.s.apple.authenticate(await apple(), null, '', 'ko')).resolves.toBeTruthy();
    const strict = build({ OAUTH_APPLE_REQUIRE_NONCE: 'true' });
    await expect(strict.s.apple.authenticate(await apple(), null, null, 'ko')).rejects.toMatchObject(apiError(401, 'auth.apple.nonce-required'));
    await expect(strict.s.apple.authenticate(await apple(), null, '', 'ko')).rejects.toMatchObject(apiError(401, 'auth.apple.nonce-required'));
  });

  it('만료·위조 서명·sub 누락은 거부', async () => {
    const { s } = build();
    await expect(s.apple.authenticate(await apple({ iat: IAT - 1000, exp: IAT - 1 }), null, null, 'ko')).rejects.toMatchObject(
      apiError(401, 'auth.apple.identity-token-expired'),
    );
    await expect(s.apple.authenticate(await apple({}, otherKey), null, null, 'ko')).rejects.toMatchObject(
      apiError(401, 'auth.apple.identity-token-invalid'),
    );
    await expect(s.apple.authenticate(await apple({ sub: null }), null, null, 'ko')).rejects.toMatchObject(
      apiError(401, 'auth.apple.identity-token-invalid'),
    );
  });

  it('빈 토큰 400, 번들 ID 미설정 503(JWKS 호출 없음), JWKS 장애 503', async () => {
    await expect(build().s.apple.authenticate('', null, null, 'ko')).rejects.toMatchObject(apiError(400, 'auth.apple.identity-token-blank'));
    const unset = build({ OAUTH_APPLE_BUNDLE_ID: '' });
    await expect(unset.s.apple.authenticate(await apple(), null, null, 'ko')).rejects.toMatchObject(apiError(503, 'auth.apple.unavailable'));
    expect(unset.calls).toHaveLength(0);
    const stub = stubFetch({ [APPLE_JWKS]: new Error('network down') });
    const down = createServices(testConfig(), { clock: () => T0, fetch: stub.fn });
    await expect(down.apple.authenticate(await apple(), null, null, 'ko')).rejects.toMatchObject(
      apiError(503, 'auth.apple.verification-failed'),
    );
  });
});
