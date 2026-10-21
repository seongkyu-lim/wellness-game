/**
 * Cloudflare Workers 진입점(`npm run dev` = wrangler dev, `npm run deploy`).
 * - DB: Hyperdrive 바인딩(HYPERDRIVE)의 connectionString 으로 요청마다 postgres 클라이언트를 만들고 응답 뒤 닫는다
 *   (Hyperdrive 가 연결 풀을 맡는다). 바인딩이 없으면 DATABASE_URL 로 직접 연결한다(로컬 wrangler dev).
 * - 설정·서비스(인메모리 상태 포함)는 isolate 당 한 번 만든다.
 * - Cron Trigger(wrangler.toml)가 scheduled() 로 리프레시 토큰 정리를 실행한다.
 */
import type { ExecutionContext } from 'hono';
import { createApp } from './app.js';
import { loadConfig } from './config.js';
import { createDb, type Db } from './db/client.js';
import type { AppContext } from './http/env.js';
import { logger } from './lib/logger.js';
import { purgeRefreshTokens } from './scheduled.js';
import { createServices } from './services.js';

interface HyperdriveBinding {
  connectionString: string;
}

export interface WorkerEnv {
  HYPERDRIVE?: HyperdriveBinding;
  [key: string]: unknown;
}

interface ScheduledController {
  cron: string;
  scheduledTime: number;
}

type App = ReturnType<typeof createApp>;

let cached: { env: WorkerEnv; app: App; services: ReturnType<typeof createServices>; databaseUrl: string } | null = null;
const requestDbs = new WeakMap<Request, Db>();

function databaseUrl(env: WorkerEnv, fallback: string): string {
  return env.HYPERDRIVE?.connectionString ?? fallback;
}

function init(env: WorkerEnv) {
  if (cached && cached.env === env) return cached;
  const config = loadConfig(env);
  const services = createServices(config);
  const url = databaseUrl(env, config.databaseUrl);
  const app = createApp({
    services,
    platform: {
      name: 'workers',
      db: (c: AppContext) => {
        let db = requestDbs.get(c.req.raw);
        if (!db) {
          db = createDb(url, { max: 5, idleTimeoutSeconds: 5 });
          requestDbs.set(c.req.raw, db);
        }
        return db;
      },
    },
    before: [
      async (c, next) => {
        try {
          await next();
        } finally {
          const db = requestDbs.get(c.req.raw);
          if (db) c.executionCtx.waitUntil(db.end({ timeout: 5 }));
        }
      },
    ],
  });
  cached = { env, app, services, databaseUrl: url };
  return cached;
}

export default {
  async fetch(request: Request, env: WorkerEnv, ctx: ExecutionContext): Promise<Response> {
    let instance;
    try {
      instance = init(env);
    } catch (error) {
      // 설정 오류(AUTH_JWT_SECRET 누락 등): Spring 의 기동 실패에 해당. 상세는 로그에만 남긴다
      logger.error('설정 오류로 요청을 처리할 수 없습니다.', error);
      return new Response(JSON.stringify({ status: 'DOWN' }), { status: 503, headers: { 'Content-Type': 'application/json' } });
    }
    return instance.app.fetch(request, env, ctx);
  },

  async scheduled(_controller: ScheduledController, env: WorkerEnv, ctx: ExecutionContext): Promise<void> {
    const { services, databaseUrl: url } = init(env);
    const db = createDb(url, { max: 1, idleTimeoutSeconds: 5 });
    ctx.waitUntil(purgeRefreshTokens(services, db).finally(() => db.end({ timeout: 5 })));
  },
};
