import type { MiddlewareHandler } from 'hono';
import { bodyLimit } from 'hono/body-limit';
import { cors } from 'hono/cors';
import { defaultErrorBody, forbidden, tooManyRequests, unauthorized } from '../lib/errors.js';
import { resolveLocale } from '../lib/i18n.js';
import type { Services } from '../services.js';
import { resolveClientIp } from './client-ip.js';
import type { AppEnv, Platform } from './env.js';

type Middleware = MiddlewareHandler<AppEnv>;

/** 요청 공통 상태: 서비스, 로케일(Accept-Language ko/en), 클라이언트 IP */
export function requestContext(services: Services, platform: Platform): Middleware {
  return async (c, next) => {
    c.set('services', services);
    c.set('locale', resolveLocale(c.req.header('accept-language')));
    c.set(
      'clientIp',
      resolveClientIp({
        platform: platform.name,
        cfConnectingIp: c.req.header('cf-connecting-ip'),
        forwardedForLines: platform.forwardedForLines
          ? platform.forwardedForLines(c)
          : [c.req.header('x-forwarded-for')].filter((v): v is string => v !== undefined),
        remoteAddress: platform.remoteAddress?.(c),
        trustForwardedHeader: services.config.rateLimit.trustForwardedHeader,
        trustedProxyHops: services.config.rateLimit.trustedProxyHops,
      }),
    );
    await next();
  };
}

/**
 * 모든 응답의 보안 헤더(#104): HSTS, nosniff, X-Frame-Options DENY, frame-ancestors 'none', Referrer-Policy no-referrer.
 * 오류 응답·404 에도 붙도록 next() 뒤에 최종 응답에 설정한다.
 */
export const API_CSP = "frame-ancestors 'none'";
/** Swagger UI 는 @hono/swagger-ui 가 cdn.jsdelivr.net 에서 스크립트·스타일을 읽는다 */
export const SWAGGER_CSP =
  "default-src 'self'; script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; " +
  "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; img-src 'self' data: https://cdn.jsdelivr.net; frame-ancestors 'none'";

export function securityHeaders(hsts: string): Middleware {
  return async (c, next) => {
    await next();
    const headers = c.res.headers;
    if (hsts) headers.set('Strict-Transport-Security', hsts);
    headers.set('X-Content-Type-Options', 'nosniff');
    headers.set('X-Frame-Options', 'DENY');
    const path = c.req.path;
    headers.set('Content-Security-Policy', path.startsWith('/swagger-ui/') || path === '/swagger-ui.html' ? SWAGGER_CSP : API_CSP);
    headers.set('Referrer-Policy', 'no-referrer');
  };
}

/**
 * Spring WebConfig#addCorsMappings("/api/**") 와 같은 허용 목록·메서드·헤더. 자격 증명(쿠키)은 허용하지 않는다.
 * 다른 출처의 요청(Origin 이 있고 요청 주소의 출처와 다름)인데 허용 목록에 없으면 Spring DefaultCorsProcessor 처럼
 * preflight·실제 요청 모두 403 "Invalid CORS request" 로 끝낸다.
 */
export function apiCors(allowedOrigins: string[]): Middleware {
  const any = allowedOrigins.includes('*');
  const handler = cors({
    origin: (origin) => (any ? '*' : allowedOrigins.includes(origin) ? origin : null),
    allowMethods: ['GET', 'POST', 'OPTIONS'],
    allowHeaders: ['Authorization', 'Content-Type'],
    exposeHeaders: ['WWW-Authenticate', 'Retry-After'],
    maxAge: 1800,
  }) as Middleware;
  return async (c, next) => {
    const origin = c.req.header('origin');
    if (origin !== undefined && !any && !allowedOrigins.includes(origin) && origin !== new URL(c.req.url).origin) {
      return c.text('Invalid CORS request', 403);
    }
    return handler(c, next);
  };
}

/** 본문 크기 상한. 넘으면 413(스프링 기본 오류 본문 형식). Content-Length 가 없어도 읽는 도중에 끊는다 */
export function maxBody(maxSize: number): Middleware {
  return bodyLimit({
    maxSize,
    onError: (c) => c.json(defaultErrorBody(413, c.req.path), 413),
  }) as Middleware;
}

/** 인증 응답(토큰이 든 본문)은 어디에도 저장하지 않게 한다 */
export function noStore(): Middleware {
  return async (c, next) => {
    await next();
    c.res.headers.set('Cache-Control', 'no-store');
  };
}

const isPreflight = (method: string, header: (name: string) => string | undefined) =>
  method === 'OPTIONS' && !!header('origin') && !!header('access-control-request-method');

/**
 * 경로별 레이트 리밋(Spring RateLimitInterceptors + #102). 1분 고정 윈도우, 한도 0 이하면 끈다. preflight 는 세지 않는다.
 * key(c) 가 null 이면 세지 않는다.
 */
export function rateLimit(
  scope: string,
  limitOf: (s: Services) => number,
  key: (c: Parameters<Middleware>[0]) => string | null,
): Middleware {
  return async (c, next) => {
    const services = c.get('services');
    const max = limitOf(services);
    if (max > 0 && !isPreflight(c.req.method, (n) => c.req.header(n))) {
      const k = key(c);
      if (k !== null) {
        const decision = await services.limiter.tryAcquire(`${scope}:${k}`, max);
        if (!decision.allowed) throw tooManyRequests(decision.retryAfterSeconds);
      }
    }
    await next();
  };
}

/** 인증이 필요 없는 /api 경로(Spring WebConfig excludePathPatterns). 새 /api/auth/** 가 조용히 공개되지 않게 명시한다 */
const PUBLIC_PATHS = new Set([
  '/api/auth/signup',
  '/api/auth/login',
  '/api/auth/google/native',
  '/api/auth/kakao/native',
  '/api/auth/naver/native',
  '/api/auth/apple/native',
  '/api/auth/refresh',
  '/api/auth/logout',
]);
const PUBLIC_PROVIDER_PATH = /^\/api\/auth\/(kakao|naver|google)$/;

export function isPublicPath(path: string): boolean {
  return PUBLIC_PATHS.has(path) || PUBLIC_PROVIDER_PATH.test(path);
}

/** 조회 전용 토큰(scope=read)이 GET 으로 부를 수 있는 경로(Spring AuthenticationInterceptor.READ_ONLY_PATHS) */
const READ_ONLY_PATHS = [/^\/api\/characters\/me$/, /^\/api\/characters\/[^/]+$/, /^\/api\/health-activities$/];

export function isReadOnlyRequest(method: string, path: string): boolean {
  return method === 'GET' && READ_ONLY_PATHS.some((p) => p.test(path));
}

/**
 * 보호 API 의 Authorization: Bearer <jwt> 검증(Spring AuthenticationInterceptor).
 * - 헤더 없음 → 401 auth.login-required, Bearer 아님·빈 토큰 → 401 auth.token-invalid
 * - 무효화 기준 이전에 발급된 토큰 → 401 auth.session-revoked
 * - 조회 전용 토큰으로 허용 목록 밖 요청 → 403 auth.read-only-token
 * - CORS preflight 는 토큰 없이 통과
 */
export function authenticate(platform: Platform): Middleware {
  return async (c, next) => {
    const path = c.req.path;
    if (isPublicPath(path) || isPreflight(c.req.method, (n) => c.req.header(n))) return next();
    const services = c.get('services');
    const header = c.req.header('authorization');
    if (!header || !header.trim()) throw unauthorized('auth.login-required');
    if (header.length < 7 || header.slice(0, 7).toLowerCase() !== 'bearer ') throw unauthorized('auth.token-invalid');
    const token = header.slice(7).trim();
    if (!token) throw unauthorized('auth.token-invalid');
    const principal = await services.jwt.authenticate(token);
    if (await services.validity.isRevoked(platform.db(c), principal.userId, principal.issuedAt, principal.scope)) {
      throw unauthorized('auth.session-revoked');
    }
    if (principal.scope === 'read' && !isReadOnlyRequest(c.req.method, path)) throw forbidden('auth.read-only-token');
    c.set('principal', principal);
    await next();
  };
}
