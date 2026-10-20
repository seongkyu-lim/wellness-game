import { SignJWT, errors as joseErrors, jwtVerify } from 'jose';
import type { AppConfig } from '../config.js';
import { unauthorized, unauthorizedCaused } from '../lib/errors.js';

/** 액세스 토큰 권한 범위. JWT 의 scope 클레임으로 싣고, 클레임이 없으면 full(Spring TokenScope) */
export type TokenScope = 'full' | 'read';

export const SCOPE_CLAIM = 'scope';
export const BEARER = 'Bearer';

export interface IssuedToken {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
}

export interface AccessTokenPrincipal {
  userId: string;
  scope: TokenScope;
  /** iat(epoch 초). 없으면 null */
  issuedAt: number | null;
}

/**
 * HS256 액세스 토큰 발급·검증(Spring JwtTokenService 와 같은 형식이라 두 서버의 토큰을 서로 쓸 수 있다).
 * - 헤더 {"alg":"HS256"}, 클레임 sub·iss·aud(배열 1개)·iat·exp, 조회 전용이면 scope:"read"
 * - 키는 AUTH_JWT_SECRET 의 UTF-8 바이트 그대로
 * - iss·aud 가 설정과 같아야 한다. 둘 다 없는 #55 이전 토큰은 iat 가 legacy cutoff 이전일 때만 통과
 */
export class JwtTokenService {
  private readonly key: Uint8Array;

  constructor(
    private readonly config: AppConfig['jwt'],
    private readonly clock: () => number = Date.now,
  ) {
    this.key = new TextEncoder().encode(config.secret);
  }

  /** 전체 권한 액세스 토큰 */
  issue(userId: string): Promise<IssuedToken> {
    return this.issueWith(userId, 'full', this.config.ttlSeconds);
  }

  /** Watch 전용 조회 토큰(scope=read, #76). 유효 기간은 AUTH_WATCH_TOKEN_TTL */
  issueWatch(userId: string): Promise<IssuedToken> {
    return this.issueWith(userId, 'read', this.config.watchTtlSeconds);
  }

  /**
   * 리프레시 family 의 범위대로 발급한다. 조회 전용 family(웹 코드 교환 로그인, #105)의 액세스 토큰은
   * scope=read 에 일반 액세스 토큰 유효 기간(AUTH_JWT_TTL)을 쓴다.
   */
  issueForScope(userId: string, scope: TokenScope): Promise<IssuedToken> {
    return this.issueWith(userId, scope, this.config.ttlSeconds);
  }

  private async issueWith(userId: string, scope: TokenScope, lifetimeSeconds: number): Promise<IssuedToken> {
    if (!userId || !userId.trim()) throw new Error('토큰 주체(userId)가 비어 있습니다.');
    const now = Math.floor(this.clock() / 1000);
    const claims: Record<string, unknown> = {};
    if (scope === 'read') claims[SCOPE_CLAIM] = 'read';
    const token = await new SignJWT(claims)
      .setProtectedHeader({ alg: 'HS256' })
      .setSubject(userId)
      .setIssuer(this.config.issuer)
      .setAudience([this.config.audience])
      .setIssuedAt(now)
      .setExpirationTime(now + lifetimeSeconds)
      .sign(this.key);
    return { accessToken: token, tokenType: BEARER, expiresIn: lifetimeSeconds };
  }

  /**
   * 토큰을 검증하고 주체·범위·발급 시각을 돌려준다.
   * @throws ApiError(401) 서명 불일치, 형식 오류, 만료, sub/exp 누락, iss/aud 불일치, 모르는 scope
   */
  async authenticate(token: string | null | undefined): Promise<AccessTokenPrincipal> {
    if (!token || !token.trim()) throw unauthorized('auth.login-required');
    let payload: Record<string, unknown>;
    try {
      const result = await jwtVerify(token, this.key, {
        algorithms: ['HS256'],
        currentDate: new Date(this.clock()),
      });
      payload = result.payload as Record<string, unknown>;
    } catch (error) {
      if (error instanceof joseErrors.JWTExpired) throw unauthorizedCaused(error, 'auth.token-expired');
      throw unauthorizedCaused(error, 'auth.token-invalid');
    }
    const sub = payload.sub;
    if (typeof payload.exp !== 'number' || typeof sub !== 'string' || !sub.trim() || !this.hasExpectedIssuerAndAudience(payload)) {
      throw unauthorized('auth.token-invalid');
    }
    const scopeClaim = payload[SCOPE_CLAIM];
    const scope: TokenScope | null = scopeClaim === undefined || scopeClaim === null ? 'full' : scopeClaim === 'read' ? 'read' : null;
    if (scope === null) throw unauthorized('auth.token-invalid');
    return { userId: sub, scope, issuedAt: typeof payload.iat === 'number' ? payload.iat : null };
  }

  private hasExpectedIssuerAndAudience(payload: Record<string, unknown>): boolean {
    const issuer = payload.iss;
    const audience = payload.aud === undefined || payload.aud === null ? [] : Array.isArray(payload.aud) ? payload.aud : [payload.aud];
    if ((issuer === undefined || issuer === null) && audience.length === 0) {
      const cutoff = this.config.legacyCutoffEpochSeconds;
      return cutoff !== null && typeof payload.iat === 'number' && payload.iat < cutoff;
    }
    return issuer === this.config.issuer && audience.includes(this.config.audience);
  }
}
