import type { Hono } from 'hono';
import type { TokenScope } from '../auth/jwt.js';
import type { SocialLoginResult } from '../auth/social.js';
import type { Db } from '../db/client.js';
import { USERNAME_PATTERN } from '../auth/password.js';
import { maxUtf8Bytes, notBlank, pattern, readJsonObject, size, str, validate } from '../http/body.js';
import { authenticatedUserId, type AppContext, type AppEnv, type Platform } from '../http/env.js';

/** 외부 토큰 길이 상한. JWT·OAuth 토큰은 이보다 훨씬 짧다 */
export const MAX_TOKEN_LENGTH = 4096;
/** 서버 리프레시 토큰은 43자(base64url 32바이트)다 */
export const MAX_REFRESH_TOKEN_LENGTH = 256;
export const MAX_NONCE_LENGTH = 256;
/** RFC 7636 code_verifier: 43~128자, [A-Z a-z 0-9 - . _ ~] (#105) */
export const CODE_VERIFIER_PATTERN = /^[A-Za-z0-9\-._~]{43,128}$/;
export const MAX_DISPLAY_NAME_LENGTH = 64;
export const MAX_PASSWORD_BYTES = 72;

/**
 * 로그인·회원가입·세션(Spring AuthController). 로그인 응답은 공통 토큰 필드(userId, displayName, accessToken, tokenType,
 * expiresIn, refreshToken, refreshExpiresIn)를 담는다. 공개·보호 경로 구분은 http/middleware.ts 의 isPublicPath.
 *
 * 리터럴 경로(/google/native 등)를 /:provider 보다 먼저 등록해야 한다.
 */
export function registerAuthRoutes(app: Hono<AppEnv>, platform: Platform) {
  /** 로그인 한 번에 발급하는 액세스·리프레시 토큰 쌍. scope=read 는 웹 코드 교환 로그인(#105) */
  async function newSession(c: AppContext, db: Db, userId: string, scope: TokenScope) {
    const { jwt, refresh } = c.get('services');
    const access = scope === 'read' ? await jwt.issueForScope(userId, 'read') : await jwt.issue(userId);
    return { access, refresh: await refresh.issue(db, userId, scope) };
  }

  async function socialLoginResponse(c: AppContext, result: SocialLoginResult, scope: TokenScope = 'full') {
    const session = await newSession(c, platform.db(c), result.userId, scope);
    return c.json({
      userId: result.userId,
      displayName: result.displayName,
      accessToken: session.access.accessToken,
      tokenType: session.access.tokenType,
      expiresIn: session.access.expiresIn,
      refreshToken: session.refresh.refreshToken,
      refreshExpiresIn: session.refresh.expiresIn,
    });
  }

  async function passwordResponse(c: AppContext, result: { userIdentifier: string; userId: string; displayName: string | null }) {
    const session = await newSession(c, platform.db(c), result.userId, 'full');
    return c.json({
      userIdentifier: result.userIdentifier,
      userId: result.userId,
      displayName: result.displayName,
      accessToken: session.access.accessToken,
      tokenType: session.access.tokenType,
      expiresIn: session.access.expiresIn,
      refreshToken: session.refresh.refreshToken,
      refreshExpiresIn: session.refresh.expiresIn,
    });
  }

  async function readRefreshToken(c: AppContext): Promise<string> {
    const body = await readJsonObject(c);
    const refreshToken = str(body, 'refreshToken');
    validate(
      notBlank('refreshToken', refreshToken),
      size('refreshToken', refreshToken, 0, MAX_REFRESH_TOKEN_LENGTH, 'validation.token.size'),
    );
    return refreshToken!;
  }

  app.post('/api/auth/signup', async (c) => {
    const body = await readJsonObject(c);
    const username = str(body, 'username');
    const password = str(body, 'password');
    const displayName = str(body, 'displayName');
    validate(
      notBlank('username', username),
      pattern('username', username, USERNAME_PATTERN, 'validation.username.pattern'),
      notBlank('password', password),
      size('password', password, 8, 72, 'validation.password.size'),
      maxUtf8Bytes('password', password, MAX_PASSWORD_BYTES, 'validation.password.bytes'),
      size('displayName', displayName, 0, MAX_DISPLAY_NAME_LENGTH, 'validation.display-name.size'),
    );
    const result = await c.get('services').password.signUp(platform.db(c), username!, password!, displayName);
    return passwordResponse(c, result);
  });

  app.post('/api/auth/login', async (c) => {
    const body = await readJsonObject(c);
    const username = str(body, 'username');
    const password = str(body, 'password');
    validate(notBlank('username', username), notBlank('password', password));
    const result = await c.get('services').password.signIn(platform.db(c), username!, password!, c.get('clientIp'));
    return passwordResponse(c, result);
  });

  /** iOS Google Sign-In ID 토큰 교환 */
  app.post('/api/auth/google/native', async (c) => {
    const body = await readJsonObject(c);
    const idToken = str(body, 'idToken');
    validate(notBlank('idToken', idToken), size('idToken', idToken, 0, MAX_TOKEN_LENGTH, 'validation.token.size'));
    return socialLoginResponse(c, await c.get('services').google.authenticate(idToken!));
  });

  /** iOS 카카오 SDK 액세스 토큰 교환. 토큰의 app_id 가 이 앱인지 확인한다 */
  app.post('/api/auth/kakao/native', async (c) => {
    const body = await readJsonObject(c);
    const accessToken = str(body, 'accessToken');
    validate(notBlank('accessToken', accessToken), size('accessToken', accessToken, 0, MAX_TOKEN_LENGTH, 'validation.token.size'));
    return socialLoginResponse(c, await c.get('services').kakao.authenticate(accessToken!));
  });

  /** iOS 네이버 SDK 토큰 교환. refreshToken 갱신으로 앱 소유를 확인한다 */
  app.post('/api/auth/naver/native', async (c) => {
    const body = await readJsonObject(c);
    const accessToken = str(body, 'accessToken');
    const refreshToken = str(body, 'refreshToken');
    validate(
      notBlank('accessToken', accessToken),
      size('accessToken', accessToken, 0, MAX_TOKEN_LENGTH, 'validation.token.size'),
      notBlank('refreshToken', refreshToken),
      size('refreshToken', refreshToken, 0, MAX_TOKEN_LENGTH, 'validation.token.size'),
    );
    return socialLoginResponse(c, await c.get('services').naver.authenticate(accessToken!, refreshToken!));
  });

  /** iOS Sign in with Apple. fullName 은 Apple 이 최초 로그인 때만 주는 이름, nonce 는 SHA-256 전 원문(#64, 선택) */
  app.post('/api/auth/apple/native', async (c) => {
    const body = await readJsonObject(c);
    const identityToken = str(body, 'identityToken');
    const fullName = str(body, 'fullName');
    const nonce = str(body, 'nonce');
    validate(
      notBlank('identityToken', identityToken),
      size('identityToken', identityToken, 0, MAX_TOKEN_LENGTH, 'validation.token.size'),
      size('nonce', nonce, 0, MAX_NONCE_LENGTH, 'validation.token.size'),
    );
    return socialLoginResponse(c, await c.get('services').apple.authenticate(identityToken!, fullName, nonce, c.get('locale')));
  });

  /** 리프레시 토큰 회전. family 의 범위(full/read)대로 액세스 토큰을 준다. 공개 경로 */
  app.post('/api/auth/refresh', async (c) => {
    const refreshToken = await readRefreshToken(c);
    const { refresh, jwt } = c.get('services');
    const rotation = await refresh.rotate(platform.db(c), refreshToken);
    const access = await jwt.issueForScope(rotation.userId, rotation.scope);
    return c.json({
      userId: rotation.userId,
      accessToken: access.accessToken,
      tokenType: access.tokenType,
      expiresIn: access.expiresIn,
      refreshToken: rotation.refreshToken.refreshToken,
      refreshExpiresIn: rotation.refreshToken.expiresIn,
    });
  });

  /**
   * 이 기기 로그아웃: 리프레시 토큰 family 를 폐기하고 그 사용자의 조회 전용 토큰도 무효로 만든다(#103).
   * 공개 경로(토큰 소유가 곧 권한). 토큰이 없거나 이미 폐기됐어도 같은 204
   */
  app.post('/api/auth/logout', async (c) => {
    const refreshToken = await readRefreshToken(c);
    await c.get('services').refresh.revokeFamily(platform.db(c), refreshToken);
    return c.body(null, 204);
  });

  /** 모든 기기 로그아웃: 리프레시 토큰을 모두 폐기하고 이미 발급된 액세스 토큰도 무효로 만든다 */
  app.post('/api/auth/logout-all', async (c) => {
    await c.get('services').refresh.revokeAll(platform.db(c), authenticatedUserId(c));
    return c.body(null, 204);
  });

  /** Watch 전용 조회 토큰(scope=read, #76). 전체 권한 토큰으로만 발급된다(조회 토큰은 인증 미들웨어가 403) */
  app.post('/api/auth/watch-token', async (c) => c.json(await c.get('services').jwt.issueWatch(authenticatedUserId(c))));

  /** 웹 authorization code 교환. 웹은 조회 전용 대시보드라 scope=read 토큰과 read family 를 준다(#105) */
  app.post('/api/auth/:provider', async (c) => {
    const body = await readJsonObject(c);
    const code = str(body, 'code');
    const redirectUri = str(body, 'redirectUri');
    const codeVerifier = str(body, 'codeVerifier');
    const provider = c.req.param('provider');
    // 형식만 여기서 본다. 공급자별 필수 여부(구글·카카오)는 SocialAuthService 가 503 설정 확인 뒤에 본다(Spring 과 같은 순서)
    validate(
      notBlank('code', code),
      notBlank('redirectUri', redirectUri),
      pattern('codeVerifier', codeVerifier, CODE_VERIFIER_PATTERN, 'validation.code-verifier.pattern'),
    );
    const result = await c.get('services').social.authenticate(provider, code!, redirectUri!, codeVerifier);
    return socialLoginResponse(c, result, 'read');
  });
}
