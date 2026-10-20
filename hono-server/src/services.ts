import { HealthActivitySyncService } from './activity/sync-service.js';
import { HealthActivitySyncValidator } from './activity/validator.js';
import { HttpClient, type FetchFn } from './auth/http-client.js';
import { AppleNativeAuthService, GoogleNativeAuthService } from './auth/id-token.js';
import { JwtTokenService } from './auth/jwt.js';
import { KakaoNativeAuthService, NaverNativeAuthService } from './auth/native-providers.js';
import { PasswordAuthService } from './auth/password.js';
import { RefreshTokenService } from './auth/refresh-token.js';
import { SocialAuthService } from './auth/social.js';
import { TokenValidityService } from './auth/token-validity.js';
import type { AppConfig } from './config.js';
import { FixedWindowRateLimiter, MemoryRateLimitStore, type RateLimitStore } from './http/rate-limit.js';

/** 설정 하나에 대한 서비스 묶음. 인메모리 상태(레이트 리밋·로그인 잠금·토큰 기준 캐시·JWKS)를 담으므로 프로세스(isolate)당 하나 */
export interface Services {
  config: AppConfig;
  clock: () => number;
  jwt: JwtTokenService;
  validity: TokenValidityService;
  refresh: RefreshTokenService;
  password: PasswordAuthService;
  social: SocialAuthService;
  kakao: KakaoNativeAuthService;
  naver: NaverNativeAuthService;
  google: GoogleNativeAuthService;
  apple: AppleNativeAuthService;
  validator: HealthActivitySyncValidator;
  sync: HealthActivitySyncService;
  limiter: FixedWindowRateLimiter;
}

export interface ServiceOptions {
  clock?: () => number;
  /** 외부 인증 서버 호출(테스트에서 가짜로 바꾼다) */
  fetch?: FetchFn;
  /** 레이트 리밋 카운터 저장소. 기본 인메모리 */
  rateLimitStore?: RateLimitStore;
}

export function createServices(config: AppConfig, options: ServiceOptions = {}): Services {
  const clock = options.clock ?? Date.now;
  const http = new HttpClient(options.fetch ?? ((input, init) => fetch(input, init)), config.httpTimeoutMs);
  const validity = new TokenValidityService(clock, config.rateLimit.maxKeys);
  const social = new SocialAuthService(config.oauth, http);
  const validator = new HealthActivitySyncValidator(config.timezone, clock);
  return {
    config,
    clock,
    jwt: new JwtTokenService(config.jwt, clock),
    validity,
    refresh: new RefreshTokenService(config.refresh, validity, clock),
    password: new PasswordAuthService(config.lockout, clock, config.rateLimit.maxKeys),
    social,
    kakao: new KakaoNativeAuthService(config.oauth.kakao, http, social),
    naver: new NaverNativeAuthService(config.oauth.naver, http, social),
    google: new GoogleNativeAuthService(config.oauth, http, clock),
    apple: new AppleNativeAuthService(config.oauth.apple, http, clock),
    validator,
    sync: new HealthActivitySyncService(validator, config.timezone, clock),
    limiter: new FixedWindowRateLimiter(options.rateLimitStore ?? new MemoryRateLimitStore(clock, config.rateLimit.maxKeys), clock),
  };
}
