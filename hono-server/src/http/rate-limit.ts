import { BoundedExpiringStore } from '../lib/bounded-store.js';

export const WINDOW_MS = 60_000;

/**
 * 고정 윈도우 카운터 저장소. 기본은 인메모리(MemoryRateLimitStore)이고,
 * 여러 인스턴스(Workers isolate·Node 프로세스)가 한도를 공유하려면 KV·Durable Object·Redis 등으로 이 인터페이스를 구현한다.
 */
export interface RateLimitStore {
  /** key 의 카운트를 1 올리고 (올린 뒤의) 카운트와 윈도우 종료 시각을 돌려준다 */
  increment(key: string, windowMs: number, nowMs: number): Promise<{ count: number; windowEndMs: number }>;
}

interface Bucket {
  windowEndMs: number;
  count: number;
}

/**
 * 인메모리 고정 윈도우(Spring FixedWindowRateLimiter). 만료 키는 윈도우마다 정리하고 maxKeys 에 닿으면 오래된 것부터 내보낸다.
 * 주의: Cloudflare Workers 에서는 isolate 마다 따로 세므로 실제 한도는 "isolate 수 × 한도"까지 느슨해질 수 있다(README).
 */
export class MemoryRateLimitStore implements RateLimitStore {
  private readonly buckets: BoundedExpiringStore<Bucket>;

  constructor(clock: () => number, maxKeys: number) {
    this.buckets = new BoundedExpiringStore(clock, WINDOW_MS, maxKeys, (b) => b.windowEndMs);
  }

  async increment(key: string, windowMs: number, nowMs: number) {
    const bucket = this.buckets.compute(key, (existing) =>
      !existing || nowMs >= existing.windowEndMs
        ? { windowEndMs: nowMs + windowMs, count: 1 }
        : { windowEndMs: existing.windowEndMs, count: existing.count + 1 },
    )!;
    return { count: bucket.count, windowEndMs: bucket.windowEndMs };
  }
}

export interface RateLimitDecision {
  allowed: boolean;
  retryAfterSeconds: number;
}

export class FixedWindowRateLimiter {
  constructor(
    private readonly store: RateLimitStore,
    private readonly clock: () => number,
  ) {}

  /** 요청 한 건을 기록하고 허용 여부를 돌려준다. limit <= 0 이면 항상 허용(비활성화) */
  async tryAcquire(key: string, limit: number): Promise<RateLimitDecision> {
    if (limit <= 0) return { allowed: true, retryAfterSeconds: 0 };
    const now = this.clock();
    const { count, windowEndMs } = await this.store.increment(key, WINDOW_MS, now);
    if (count <= limit) return { allowed: true, retryAfterSeconds: 0 };
    // 올림해서 최소 1초. Retry-After 가 0 이면 클라이언트가 즉시 재시도한다
    return { allowed: false, retryAfterSeconds: Math.max(1, Math.ceil((windowEndMs - now) / 1000)) };
  }
}
