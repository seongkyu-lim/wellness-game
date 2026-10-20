import { isValidZone } from './lib/dates.js';
import { parseDurationMs } from './lib/duration.js';
import { Instant } from './lib/instant.js';

/**
 * 환경변수 설정. 이름과 기본값은 Spring backend/src/main/resources/application.yml 과 같다.
 * Hono 에만 있는 값(Spring 미반영 #102·#103·#105 항목 포함)은 README "환경변수" 표에 표시했다.
 */
export interface AppConfig {
  timezone: string;
  databaseUrl: string;
  dbPoolMax: number;
  apiDocsEnabled: boolean;
  corsAllowedOrigins: string[];
  /** 외부 인증 서버 호출 제한 시간(Spring: connect 3s + read 5s) */
  httpTimeoutMs: number;
  /** HSTS 헤더 값. 빈 문자열이면 보내지 않는다 */
  hstsHeader: string;
  jwt: {
    secret: string;
    ttlSeconds: number;
    watchTtlSeconds: number;
    issuer: string;
    audience: string;
    /** iss·aud 없는 #55 이전 토큰을 받아 줄 iat 상한(epoch 초). null 이면 받지 않는다 */
    legacyCutoffEpochSeconds: number | null;
  };
  refresh: {
    ttlMs: number;
    absoluteTtlMs: number;
    /** 조회 전용(read) family(웹 코드 교환 로그인, #105): 유휴 7일 */
    readTtlMs: number;
    /** 조회 전용(read) family: 절대 30일 */
    readAbsoluteTtlMs: number;
    reuseGraceMs: number;
    revokedRetentionMs: number;
  };
  rateLimit: {
    syncPerMinute: number;
    authPerMinute: number;
    loginPerMinute: number;
    refreshPerMinute: number;
    generalPerMinute: number;
    /** Node: true 면 X-Forwarded-For 를 본다(신뢰하는 리버스 프록시 뒤에서만) */
    trustForwardedHeader: boolean;
    /** Node: X-Forwarded-For 오른쪽부터 건너뛸 값 수. 0 이면 마지막 값(바로 앞단 프록시가 덧붙인 주소) */
    trustedProxyHops: number;
    maxKeys: number;
  };
  lockout: {
    maxFailedLogins: number;
    maxFailedLoginsPerUsername: number;
    lockoutMinutes: number;
  };
  oauth: {
    kakao: OAuthProvider & { nativeAppId: string; accessTokenInfoUri: string };
    naver: OAuthProvider;
    google: OAuthProvider;
    googleNative: { clientIds: string[]; jwksUri: string; jwksCacheTtlMs: number; jwksStaleMaxMs: number };
    apple: { bundleId: string; jwksUri: string; jwksCacheTtlMs: number; jwksStaleMaxMs: number; requireNonce: boolean };
  };
}

export interface OAuthProvider {
  clientId: string;
  clientSecret: string;
  tokenUri: string;
  profileUri: string;
}

export type EnvSource = Record<string, unknown>;

export const MIN_SECRET_BYTES = 32;
export const MIN_DISTINCT_SECRET_CHARS = 8;
/** 예시·개발용 시크릿에 들어 있는 문구. 이런 값으로는 기동하지 않는다(운영에 그대로 배포되는 사고 방지) */
export const PLACEHOLDER_SECRET_MARKERS = ['change-me', 'local-dev-only'];

export function loadConfig(env: EnvSource): AppConfig {
  const get = (key: string): string | undefined => {
    const value = env[key];
    return typeof value === 'string' ? value : undefined;
  };
  /** Spring `${KEY:default}`: 변수가 없을 때만 기본값(빈 문자열은 빈 값 그대로) */
  const str = (key: string, fallback: string) => get(key) ?? fallback;
  const int = (key: string, fallback: number) => {
    const raw = get(key);
    if (raw === undefined || raw.trim() === '') return fallback;
    if (!/^[+-]?\d+$/.test(raw.trim())) throw new Error(`${key} 는 정수여야 합니다.`);
    return Number(raw.trim());
  };
  const bool = (key: string, fallback: boolean) => {
    const raw = get(key)?.trim().toLowerCase();
    if (raw === undefined || raw === '') return fallback;
    if (raw === 'true') return true;
    if (raw === 'false') return false;
    throw new Error(`${key} 는 true 또는 false 여야 합니다.`);
  };
  const duration = (key: string, fallback: string) => {
    const raw = get(key);
    return parseDurationMs(raw === undefined || raw.trim() === '' ? fallback : raw);
  };
  const list = (key: string, fallback: string) =>
    str(key, fallback)
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);

  const timezone = str('APP_TIMEZONE', 'Asia/Seoul');
  if (!isValidZone(timezone)) throw new Error(`APP_TIMEZONE '${timezone}' 은(는) 올바른 시간대가 아닙니다.`);

  const secret = get('AUTH_JWT_SECRET') ?? '';
  const secretBytes = new TextEncoder().encode(secret).length;
  if (secretBytes < MIN_SECRET_BYTES) {
    throw new Error(`auth.jwt.secret(AUTH_JWT_SECRET)은 ${MIN_SECRET_BYTES}바이트 이상이어야 합니다. 현재 ${secretBytes}바이트.`);
  }
  // Java String.chars().distinct() 와 같게 UTF-16 코드 단위로 센다
  const distinct = new Set(Array.from({ length: secret.length }, (_, i) => secret.charCodeAt(i))).size;
  if (distinct < MIN_DISTINCT_SECRET_CHARS) {
    throw new Error(
      `auth.jwt.secret(AUTH_JWT_SECRET)이 너무 단순합니다. 서로 다른 문자가 ${MIN_DISTINCT_SECRET_CHARS}종류 이상인 무작위 값을 쓰세요(예: openssl rand -base64 48).`,
    );
  }
  if (PLACEHOLDER_SECRET_MARKERS.some((marker) => secret.toLowerCase().includes(marker))) {
    throw new Error('auth.jwt.secret(AUTH_JWT_SECRET)이 예시 값입니다. openssl rand -base64 48 로 만든 무작위 값을 쓰세요.');
  }
  const ttlSeconds = Math.trunc(duration('AUTH_JWT_TTL', '1h') / 1000);
  const watchTtlSeconds = Math.trunc(duration('AUTH_WATCH_TOKEN_TTL', '7d') / 1000);
  if (ttlSeconds < 1) throw new Error('auth.jwt.ttl(AUTH_JWT_TTL)은 1초 이상이어야 합니다.');
  if (watchTtlSeconds < 1) throw new Error('auth.jwt.watch-ttl(AUTH_WATCH_TOKEN_TTL)은 1초 이상이어야 합니다.');

  const cutoffRaw = str('AUTH_JWT_LEGACY_CUTOFF', '2026-10-06T00:00:00Z').trim();
  let legacyCutoffEpochSeconds: number | null = null;
  if (cutoffRaw) {
    const parsed = Instant.parse(cutoffRaw);
    if (!parsed) throw new Error('AUTH_JWT_LEGACY_CUTOFF 는 ISO-8601 시각이어야 합니다.');
    legacyCutoffEpochSeconds = parsed.epochSecond + parsed.nano / 1e9;
  }

  const refresh = {
    ttlMs: duration('AUTH_REFRESH_TTL', '30d'),
    absoluteTtlMs: duration('AUTH_REFRESH_ABSOLUTE_TTL', '90d'),
    readTtlMs: duration('AUTH_REFRESH_READ_TTL', '7d'),
    readAbsoluteTtlMs: duration('AUTH_REFRESH_READ_ABSOLUTE_TTL', '30d'),
    reuseGraceMs: duration('AUTH_REFRESH_REUSE_GRACE', '30s'),
    revokedRetentionMs: duration('AUTH_REFRESH_REVOKED_RETENTION', '7d'),
  };
  if (refresh.ttlMs < 1000 || refresh.readTtlMs < 1000) {
    throw new Error('auth.refresh.ttl(AUTH_REFRESH_TTL, AUTH_REFRESH_READ_TTL)은 1초 이상이어야 합니다.');
  }
  if (refresh.absoluteTtlMs < refresh.ttlMs || refresh.readAbsoluteTtlMs < refresh.readTtlMs) {
    throw new Error('auth.refresh.absolute-ttl 은 auth.refresh.ttl 이상이어야 합니다.');
  }
  if (refresh.reuseGraceMs < 0 || refresh.revokedRetentionMs < 0) {
    throw new Error('auth.refresh.reuse-grace·revoked-retention 은 0 이상이어야 합니다.');
  }

  const trustForwarded = bool('APP_RATE_LIMIT_TRUST_FORWARDED_HEADER', false);
  const rateLimit = {
    syncPerMinute: int('APP_RATE_LIMIT_SYNC_PER_MINUTE', 30),
    authPerMinute: int('APP_RATE_LIMIT_AUTH_PER_MINUTE', 30),
    loginPerMinute: int('APP_RATE_LIMIT_LOGIN_PER_MINUTE', 10),
    refreshPerMinute: int('APP_RATE_LIMIT_REFRESH_PER_MINUTE', 120),
    generalPerMinute: int('APP_RATE_LIMIT_GENERAL_PER_MINUTE', 0),
    trustForwardedHeader: trustForwarded,
    trustedProxyHops: int('APP_RATE_LIMIT_TRUSTED_PROXY_HOPS', 0),
    maxKeys: int('APP_RATE_LIMIT_MAX_KEYS', 100_000),
  };
  if (rateLimit.maxKeys < 1) throw new Error('app.rate-limit.max-keys 는 1 이상이어야 합니다.');
  if (rateLimit.trustedProxyHops < 0) throw new Error('APP_RATE_LIMIT_TRUSTED_PROXY_HOPS 는 0 이상이어야 합니다.');

  const lockout = {
    maxFailedLogins: int('APP_AUTH_MAX_FAILED_LOGINS', 5),
    maxFailedLoginsPerUsername: int('APP_AUTH_MAX_FAILED_LOGINS_PER_USERNAME', 50),
    lockoutMinutes: int('APP_AUTH_LOCKOUT_MINUTES', 15),
  };
  if ((lockout.maxFailedLogins > 0 || lockout.maxFailedLoginsPerUsername > 0) && lockout.lockoutMinutes < 1) {
    throw new Error('app.auth.lockout-minutes 는 1 이상이어야 합니다.');
  }

  const jwksCacheTtl = (key: string) => {
    const ms = duration(key, '1h');
    return ms > 0 ? ms : 3_600_000;
  };
  const jwksStaleMax = (key: string) => {
    const ms = duration(key, '24h');
    return ms >= 0 ? ms : 86_400_000;
  };

  // 네이티브 구글 로그인이 웹 로그인 클라이언트 ID 를 aud 로 받으면 웹(조회 전용)용 ID 토큰으로 전체 권한 세션을 얻을 수 있다
  const googleWebClientId = str('OAUTH_GOOGLE_CLIENT_ID', '').trim();
  const googleNativeClientIds = list('OAUTH_GOOGLE_NATIVE_CLIENT_IDS', get('OAUTH_GOOGLE_IOS_CLIENT_ID') ?? '');
  if (googleWebClientId && googleNativeClientIds.includes(googleWebClientId)) {
    throw new Error('OAUTH_GOOGLE_NATIVE_CLIENT_IDS 에 웹 로그인 클라이언트 ID(OAUTH_GOOGLE_CLIENT_ID)를 넣으면 안 됩니다.');
  }

  return {
    timezone,
    databaseUrl: resolveDatabaseUrl(get('DATABASE_URL'), get('DATABASE_USERNAME'), get('DATABASE_PASSWORD')),
    dbPoolMax: int('DB_POOL_MAX', 5),
    apiDocsEnabled: bool('APP_API_DOCS_ENABLED', false),
    corsAllowedOrigins: list('CORS_ALLOWED_ORIGINS', 'http://localhost:5173'),
    httpTimeoutMs: duration('APP_HTTP_CLIENT_TIMEOUT', '8s'),
    hstsHeader: str('APP_HSTS_HEADER', 'max-age=31536000; includeSubDomains'),
    jwt: {
      secret,
      ttlSeconds,
      watchTtlSeconds,
      issuer: str('AUTH_JWT_ISSUER', 'wellness-game').trim() || 'wellness-game',
      audience: str('AUTH_JWT_AUDIENCE', 'wellness-game-api').trim() || 'wellness-game-api',
      legacyCutoffEpochSeconds,
    },
    refresh,
    rateLimit,
    lockout,
    oauth: {
      kakao: {
        clientId: str('OAUTH_KAKAO_CLIENT_ID', ''),
        clientSecret: str('OAUTH_KAKAO_CLIENT_SECRET', ''),
        tokenUri: 'https://kauth.kakao.com/oauth/token',
        profileUri: 'https://kapi.kakao.com/v2/user/me',
        nativeAppId: str('OAUTH_KAKAO_NATIVE_APP_ID', '').trim(),
        accessTokenInfoUri: 'https://kapi.kakao.com/v1/user/access_token_info',
      },
      naver: {
        clientId: str('OAUTH_NAVER_CLIENT_ID', ''),
        clientSecret: str('OAUTH_NAVER_CLIENT_SECRET', ''),
        tokenUri: 'https://nid.naver.com/oauth2.0/token',
        profileUri: 'https://openapi.naver.com/v1/nid/me',
      },
      google: {
        clientId: str('OAUTH_GOOGLE_CLIENT_ID', ''),
        clientSecret: str('OAUTH_GOOGLE_CLIENT_SECRET', ''),
        tokenUri: 'https://oauth2.googleapis.com/token',
        profileUri: 'https://openidconnect.googleapis.com/v1/userinfo',
      },
      googleNative: {
        // 허용 aud·azp 전체(iOS·Android·Android 전용 서버 클라이언트). 웹 로그인 클라이언트 ID 는 넣지 않는다
        clientIds: googleNativeClientIds,
        jwksUri: 'https://www.googleapis.com/oauth2/v3/certs',
        jwksCacheTtlMs: jwksCacheTtl('OAUTH_GOOGLE_JWKS_CACHE_TTL'),
        jwksStaleMaxMs: jwksStaleMax('OAUTH_GOOGLE_JWKS_STALE_MAX'),
      },
      apple: {
        bundleId: str('OAUTH_APPLE_BUNDLE_ID', '').trim(),
        jwksUri: 'https://appleid.apple.com/auth/keys',
        jwksCacheTtlMs: jwksCacheTtl('OAUTH_APPLE_JWKS_CACHE_TTL'),
        jwksStaleMaxMs: jwksStaleMax('OAUTH_APPLE_JWKS_STALE_MAX'),
        requireNonce: bool('OAUTH_APPLE_REQUIRE_NONCE', false),
      },
    },
  };
}

/**
 * Spring 형식(jdbc:postgresql://host:5432/db?sslmode=require + DATABASE_USERNAME/PASSWORD)과
 * 일반 형식(postgres://user:pass@host:5432/db) 모두 받는다. Spring 과 같은 로컬 기본값.
 */
export function resolveDatabaseUrl(url: string | undefined, username: string | undefined, password: string | undefined): string {
  const raw = url && url.trim() ? url.trim() : 'jdbc:postgresql://localhost:5432/wellness_game';
  const withoutJdbc = raw.startsWith('jdbc:') ? raw.slice('jdbc:'.length) : raw;
  const parsed = new URL(withoutJdbc);
  if (!parsed.username) {
    parsed.username = encodeURIComponent(username ?? 'wellness');
    if (!parsed.password) parsed.password = encodeURIComponent(password ?? 'wellness');
  }
  return parsed.toString();
}
