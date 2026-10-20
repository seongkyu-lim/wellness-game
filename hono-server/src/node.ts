/**
 * Node 진입점(Render·로컬). `npm run dev:node` 또는 `npm run build && npm start`.
 * 프로세스 하나가 DB 연결 풀(DB_POOL_MAX)과 인메모리 상태(레이트 리밋·로그인 잠금)를 가진다.
 */
import type { IncomingMessage } from 'node:http';
import { serve } from '@hono/node-server';
import { getConnInfo } from '@hono/node-server/conninfo';
import { createApp } from './app.js';
import { loadConfig } from './config.js';
import { createDb } from './db/client.js';
import type { AppContext } from './http/env.js';
import { logger } from './lib/logger.js';
import { scheduleDailyCleanup } from './scheduled.js';
import { createServices } from './services.js';

const config = loadConfig(process.env);
const db = createDb(config.databaseUrl, { max: config.dbPoolMax });
const services = createServices(config);
const app = createApp({
  services,
  platform: {
    name: 'node',
    db: () => db,
    remoteAddress: (c: AppContext) => getConnInfo(c).remote.address,
    forwardedForLines: (c: AppContext) => {
      const incoming = (c.env as { incoming?: IncomingMessage }).incoming;
      const raw = incoming?.rawHeaders ?? [];
      const lines: string[] = [];
      for (let i = 0; i + 1 < raw.length; i += 2) {
        if (raw[i]!.toLowerCase() === 'x-forwarded-for') lines.push(raw[i + 1]!);
      }
      return lines;
    },
  },
});

const port = Number(process.env.PORT ?? 8080);
const server = serve({ fetch: app.fetch, port }, (info) => {
  logger.info(`Wellness Game API (Hono, Node) listening on :${info.port}`);
});

// 매일 04:00(APP_TIMEZONE) 만료·폐기 리프레시 토큰 정리(Spring RefreshTokenCleanupJob)
const cleanup = scheduleDailyCleanup(services, db);

// 재배포 때 받는 SIGTERM 에서 처리 중인 요청을 마치고 종료한다(Spring server.shutdown=graceful)
for (const signal of ['SIGTERM', 'SIGINT'] as const) {
  process.once(signal, () => {
    logger.info(`${signal} 수신: 종료합니다.`);
    cleanup.stop();
    const force = setTimeout(() => process.exit(1), 30_000);
    force.unref();
    server.close(() => {
      db.end({ timeout: 5 }).finally(() => process.exit(0));
    });
  });
}
