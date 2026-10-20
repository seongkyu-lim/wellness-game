import { OpenAPIHono } from '@hono/zod-openapi';
import type { MiddlewareHandler } from 'hono';
import type { ContentfulStatusCode } from 'hono/utils/http-status';
import { UnsupportedMediaTypeError } from './http/body.js';
import type { AppContext, AppEnv, Platform } from './http/env.js';
import { apiCors, authenticate, maxBody, noStore, rateLimit, requestContext, securityHeaders } from './http/middleware.js';
import { ApiError, defaultErrorBody, errorBody, InternalError, resolve, UnreadableBodyError } from './lib/errors.js';
import { message } from './lib/i18n.js';
import { logger } from './lib/logger.js';
import { registerAuthRoutes } from './routes/auth.js';
import { registerGameRoutes } from './routes/game.js';
import { registerApiDocs } from './routes/openapi.js';
import type { Services } from './services.js';

export const MAX_BODY_BYTES = 512 * 1024;
export const MAX_AUTH_BODY_BYTES = 16 * 1024;
export const MAX_SYNC_BODY_BYTES = 256 * 1024;

export interface CreateAppOptions {
  services: Services;
  platform: Platform;
  /** 다른 모든 미들웨어보다 먼저 실행할 미들웨어(Workers 의 요청별 DB 정리 등) */
  before?: MiddlewareHandler<AppEnv>[];
}

/** 메서드가 맞지 않을 때 405 를 주기 위한 경로 목록(Spring 은 경로가 있으면 405, 없으면 404) */
const KNOWN_ROUTES: { pattern: RegExp; methods: string[] }[] = [
  { pattern: /^\/api\/auth\/[^/]+(\/native)?$/, methods: ['POST'] },
  { pattern: /^\/api\/characters\/[^/]+$/, methods: ['GET'] },
  { pattern: /^\/api\/health-activities\/sync$/, methods: ['POST'] },
  { pattern: /^\/api\/health-activities$/, methods: ['GET'] },
  { pattern: /^\/healthz$/, methods: ['GET'] },
];

export function createApp({ services, platform, before = [] }: CreateAppOptions) {
  const app = new OpenAPIHono<AppEnv>();
  const config = services.config;
  const ipKey = (c: AppContext) => c.get('clientIp');

  for (const middleware of before) app.use('*', middleware);
  app.use('*', securityHeaders(config.hstsHeader));
  app.use('*', requestContext(services, platform));
  app.use('/api/*', apiCors(config.corsAllowedOrigins));
  app.use('/api/auth/*', noStore());

  // 본문 크기 상한(인증 요청은 토큰 몇 개, 동기화는 활동 50개면 충분하다)
  app.use('*', maxBody(MAX_BODY_BYTES));
  app.use('/api/auth/*', maxBody(MAX_AUTH_BODY_BYTES));
  app.use('/api/health-activities/sync', maxBody(MAX_SYNC_BODY_BYTES));

  // 레이트 리밋(#48·#102): IP 별은 인증 전에, 동기화는 userId 별로 인증 뒤에 센다(등록 순서 = 실행 순서)
  app.use('/api/*', rateLimit('general', (s) => s.config.rateLimit.generalPerMinute, ipKey));
  app.use('/api/auth/*', rateLimit('auth', (s) => s.config.rateLimit.authPerMinute, (c) => (c.req.path === '/api/auth/refresh' ? null : ipKey(c))));
  app.use('/api/auth/login', rateLimit('login', (s) => s.config.rateLimit.loginPerMinute, ipKey));
  app.use('/api/auth/signup', rateLimit('login', (s) => s.config.rateLimit.loginPerMinute, ipKey));
  app.use('/api/auth/refresh', rateLimit('refresh', (s) => s.config.rateLimit.refreshPerMinute, ipKey));
  app.use('/api/*', authenticate(platform));
  app.use(
    '/api/health-activities/sync',
    rateLimit('sync', (s) => s.config.rateLimit.syncPerMinute, (c) => (c.req.method === 'POST' ? (c.get('principal')?.userId ?? null) : null)),
  );

  // 배포 플랫폼 헬스체크. 프로세스가 요청을 받을 수 있는지만 본다(DB 미확인, 인증·레이트 리밋 대상 아님)
  app.get('/healthz', (c) => {
    c.header('Cache-Control', 'no-store');
    return c.json({ status: 'UP' });
  });

  registerAuthRoutes(app, platform);
  registerGameRoutes(app, platform);
  if (config.apiDocsEnabled) registerApiDocs(app);

  app.notFound((c) => {
    const path = c.req.path;
    const known = KNOWN_ROUTES.find((r) => r.pattern.test(path));
    if (known && !known.methods.includes(c.req.method)) {
      c.header('Allow', known.methods.join(', '));
      return c.json(defaultErrorBody(405, path), 405);
    }
    return c.json(defaultErrorBody(404, path), 404);
  });

  app.onError((error, c) => {
    const locale = c.get('locale') ?? 'ko';
    if (error instanceof ApiError) {
      const msg = resolve(locale, error.localized);
      logFor(error.status, `${error.name}: ${msg}`, error.cause ?? error);
      for (const [name, value] of Object.entries(error.headers)) c.header(name, value);
      return c.json(errorBody(error.status, msg), error.status as ContentfulStatusCode);
    }
    if (error instanceof UnreadableBodyError) {
      // 파서 상세(필드 경로, 입력 조각)는 응답에 넣지 않는다
      logFor(400, error.name);
      return c.json(errorBody(400, message(locale, 'error.unreadable-body')), 400);
    }
    if (error instanceof UnsupportedMediaTypeError) {
      logFor(415, error.name);
      return c.json(defaultErrorBody(415, c.req.path), 415);
    }
    const status = error instanceof InternalError ? error.status : 500;
    logFor(status, `${error.name}: ${error.message}`, error);
    return c.json(errorBody(status, message(locale, 'error.generic')), status);
  });

  return app;
}

/** 4xx 는 클라이언트 문제라 스택 없이 한 줄(warn), 5xx 는 스택과 함께 error */
function logFor(status: number, summary: string, error?: unknown) {
  if (status >= 500) logger.error(`${status} 응답: ${summary}`, error);
  else logger.warn(`${status} 응답: ${summary}`);
}
