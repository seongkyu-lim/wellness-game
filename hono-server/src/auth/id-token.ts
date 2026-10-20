import { errors as joseErrors, jwtVerify, type JWTPayload } from 'jose';
import type { AppConfig } from '../config.js';
import { sha256Hex, timingSafeEqual } from '../lib/crypto.js';
import { message } from '../lib/i18n.js';
import type { Locale } from '../lib/i18n.js';
import { serviceUnavailableCaused, unauthorized, unauthorizedCaused } from '../lib/errors.js';
import { audienceOf } from './claims.js';
import type { HttpClient } from './http-client.js';
import { JwksKeyCache, JwksUnavailableError } from './jwks-cache.js';
import { requireToken, unavailable } from './native-providers.js';
import type { SocialLoginResult } from './social.js';

export const GOOGLE_ISSUERS = new Set(['accounts.google.com', 'https://accounts.google.com']);
export const APPLE_ISSUER = 'https://appleid.apple.com';
export const MAX_FULL_NAME_LENGTH = 100;

type Codes = { unavailable: string; expired: string; invalid: string };

/** 서명(RS256, kid → JWKS)·exp·nbf 만 검증한다. 나머지 클레임은 호출자가 Spring 과 같은 순서로 본다 */
async function verifySignature(token: string, keys: JwksKeyCache, clock: () => number, codes: Codes): Promise<JWTPayload> {
  try {
    const { payload } = await jwtVerify(token, keys.keyFor, { algorithms: ['RS256'], currentDate: new Date(clock()) });
    return payload;
  } catch (error) {
    if (error instanceof JwksUnavailableError) throw serviceUnavailableCaused(error, codes.unavailable);
    if (error instanceof joseErrors.JWTExpired) throw unauthorizedCaused(error, codes.expired);
    // 서명 불일치, 형식 오류, 지원하지 않는 alg, 모르는 kid 등
    throw unauthorizedCaused(error, codes.invalid);
  }
}


/**
 * iOS Google Sign-In ID 토큰을 Google JWKS 로 직접 검증해 "google:{sub}" userId 를 만든다(Spring GoogleNativeAuthService).
 * 클레임: aud ⊆ 허용 네이티브 클라이언트 ID(iOS·Android·Android 전용 서버 클라이언트), azp(있으면) ∈ 허용, iss ∈ {accounts.google.com, https://accounts.google.com}, exp·sub 필수
 */
export class GoogleNativeAuthService {
  private readonly allowedAudiences: Set<string>;
  private readonly keys: JwksKeyCache;

  constructor(
    oauth: AppConfig['oauth'],
    http: HttpClient,
    private readonly clock: () => number,
  ) {
    // 명시한 네이티브 클라이언트 ID 만 허용한다. 웹 로그인 클라이언트 ID 는 넣지 않는다(웹 XSS 로 얻은 웹용 ID 토큰이
    // 이 경로로 전체 권한 세션을 얻지 못하게, 웹 로그인은 조회 전용 #105)
    this.allowedAudiences = new Set(oauth.googleNative.clientIds.map((id) => id.trim()).filter(Boolean));
    this.keys = new JwksKeyCache(
      'Google',
      http,
      oauth.googleNative.jwksUri,
      oauth.googleNative.jwksCacheTtlMs,
      oauth.googleNative.jwksStaleMaxMs,
      clock,
    );
  }

  async authenticate(idToken: string): Promise<SocialLoginResult> {
    if (this.allowedAudiences.size === 0) {
      throw unavailable('구글', 'OAUTH_GOOGLE_NATIVE_CLIENT_IDS(또는 OAUTH_GOOGLE_IOS_CLIENT_ID) 환경변수', 'auth.google.unavailable');
    }
    requireToken(idToken, 'auth.google.id-token-blank');
    const claims = await verifySignature(idToken.trim(), this.keys, this.clock, {
      unavailable: 'auth.google.verification-failed',
      expired: 'auth.google.id-token-expired',
      invalid: 'auth.google.id-token-invalid',
    });
    const audience = audienceOf(claims);
    if (audience.length === 0 || !audience.every((aud) => this.allowedAudiences.has(aud))) {
      throw unauthorized('auth.google.wrong-audience');
    }
    const azp = claims.azp;
    if (azp !== undefined && azp !== null && !this.allowedAudiences.has(String(azp))) {
      throw unauthorized('auth.google.wrong-audience');
    }
    if (typeof claims.iss !== 'string' || !GOOGLE_ISSUERS.has(claims.iss)) throw unauthorized('auth.google.wrong-issuer');
    if (typeof claims.exp !== 'number') throw unauthorized('auth.google.id-token-invalid');
    if (claims.exp * 1000 <= this.clock()) throw unauthorized('auth.google.id-token-expired');
    if (typeof claims.sub !== 'string' || !claims.sub.trim()) throw unauthorized('auth.google.id-token-invalid');
    return { userId: `google:${claims.sub}`, displayName: typeof claims.name === 'string' ? claims.name : null };
  }
}

/**
 * iOS Sign in with Apple identity token 을 Apple JWKS 로 직접 검증해 "apple:{sub}" userId 를 만든다(Spring AppleNativeAuthService).
 * - iss = https://appleid.apple.com, aud ∋ OAUTH_APPLE_BUNDLE_ID, exp·sub 필수
 * - nonce(#64): 요청에 원문이 있으면 토큰 nonce 클레임이 SHA-256(원문) hex 와 같아야 한다. require-nonce 면 nonce 필수
 * - 식별은 sub 만(이메일은 쓰지 않음)
 */
export class AppleNativeAuthService {
  private readonly keys: JwksKeyCache | null;

  constructor(
    private readonly config: AppConfig['oauth']['apple'],
    http: HttpClient,
    private readonly clock: () => number,
  ) {
    this.keys = config.bundleId
      ? new JwksKeyCache('Apple', http, config.jwksUri, config.jwksCacheTtlMs, config.jwksStaleMaxMs, clock)
      : null;
  }

  async authenticate(
    identityToken: string,
    fullName: string | null | undefined,
    nonce: string | null | undefined,
    locale: Locale,
  ): Promise<SocialLoginResult> {
    if (!this.keys) throw unavailable('애플', 'OAUTH_APPLE_BUNDLE_ID 환경변수', 'auth.apple.unavailable');
    requireToken(identityToken, 'auth.apple.identity-token-blank');
    const claims = await verifySignature(identityToken.trim(), this.keys, this.clock, {
      unavailable: 'auth.apple.verification-failed',
      expired: 'auth.apple.identity-token-expired',
      invalid: 'auth.apple.identity-token-invalid',
    });
    // jjwt requireIssuer → requireAudience 순서(누락·불일치 모두 같은 문구)
    if (claims.iss !== APPLE_ISSUER) throw unauthorized('auth.apple.wrong-issuer');
    if (!audienceOf(claims).includes(this.config.bundleId)) throw unauthorized('auth.apple.wrong-audience');
    if (typeof claims.sub !== 'string' || !claims.sub.trim() || typeof claims.exp !== 'number') {
      throw unauthorized('auth.apple.identity-token-invalid');
    }
    await this.verifyNonce(claims, nonce);
    return { userId: `apple:${claims.sub}`, displayName: displayName(fullName, locale) };
  }

  private async verifyNonce(claims: JWTPayload, nonce: string | null | undefined) {
    if (nonce === null || nonce === undefined || nonce === '') {
      if (this.config.requireNonce) throw unauthorized('auth.apple.nonce-required');
      return;
    }
    const expected = await sha256Hex(nonce);
    const actual = claims.nonce;
    if (typeof actual !== 'string' || !timingSafeEqual(expected, actual)) throw unauthorized('auth.apple.nonce-mismatch');
  }
}

/** Apple 은 이름을 최초 로그인 때만 준다. 없으면 기본 이름(요청 로케일), 길면 코드 포인트 기준 100자로 자른다 */
function displayName(fullName: string | null | undefined, locale: Locale): string {
  if (!fullName || !fullName.trim()) return message(locale, 'auth.apple.default-display-name');
  const trimmed = fullName.trim();
  const codePoints = Array.from(trimmed);
  return codePoints.length <= MAX_FULL_NAME_LENGTH ? trimmed : codePoints.slice(0, MAX_FULL_NAME_LENGTH).join('');
}
