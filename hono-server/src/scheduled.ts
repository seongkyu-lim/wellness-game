import type { Db } from './db/client.js';
import { localDateOf, startOfDay, addDays } from './lib/dates.js';
import { Instant } from './lib/instant.js';
import { logger } from './lib/logger.js';
import type { Services } from './services.js';

/** 만료·폐기된 리프레시 토큰 행을 지운다(Spring RefreshTokenCleanupJob) */
export async function purgeRefreshTokens(services: Services, db: Db): Promise<number> {
  const deleted = await services.refresh.purgeExpiredAndRevoked(db);
  logger.info(`리프레시 토큰 정리: ${deleted}건 삭제`);
  return deleted;
}

/**
 * Node: 매일 04:00(APP_TIMEZONE) 에 정리한다. Spring 의 cron 설정(AUTH_REFRESH_CLEANUP_CRON)은 해석하지 않고 시각을 고정한다.
 * Workers 는 wrangler.toml 의 Cron Trigger 가 scheduled() 를 부른다.
 */
export function scheduleDailyCleanup(services: Services, db: Db, hour = 4): { stop(): void } {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const zone = services.config.timezone;
  /** date 의 hour 시(zone) epoch ms */
  const at = (date: string) => startOfDay(date, zone).toEpochMilli() + hour * 3_600_000;
  const scheduleNext = () => {
    const now = services.clock();
    const today = localDateOf(Instant.ofEpochMilli(now), zone);
    let next = at(today);
    if (next <= now) next = at(addDays(today, 1));
    timer = setTimeout(() => {
      purgeRefreshTokens(services, db)
        .catch((error: unknown) => logger.error('리프레시 토큰 정리 실패', error))
        .finally(scheduleNext);
    }, next - now);
    timer.unref?.();
  };
  scheduleNext();
  return { stop: () => clearTimeout(timer) };
}
