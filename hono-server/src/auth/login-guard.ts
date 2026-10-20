import type { AppConfig } from '../config.js';
import { BoundedExpiringStore } from '../lib/bounded-store.js';
import { logger } from '../lib/logger.js';

export const MAX_USERNAME_KEY_LENGTH = 64;

interface Attempts {
  failures: number;
  expiresAt: number;
}

/**
 * 로그인 실패 횟수와 잠금(Spring LoginAttemptGuard, #55).
 * - (아이디, IP): maxFailedLogins 회 / 아이디 전체: maxFailedLoginsPerUsername 회
 * - 비교 전에 시도를 실패로 예약하고(동시 요청이 한도를 넘지 못하게), 성공하면 (아이디, IP) 기록을 지우고
 *   아이디 전체 기록에서는 이번 예약 한 건만 되돌린다.
 * - 마지막 실패 후 잠금 시간이 지나면 기록을 잊는다. 인스턴스별 인메모리(Workers 에서는 isolate 별, README 참고)
 */
export class LoginAttemptGuard {
  private readonly lockoutMs: number;
  private readonly perClient: BoundedExpiringStore<Attempts>;
  private readonly perUsername: BoundedExpiringStore<Attempts>;

  constructor(
    private readonly config: AppConfig['lockout'],
    private readonly clock: () => number,
    maxKeys: number,
  ) {
    this.lockoutMs = config.lockoutMinutes * 60_000;
    // 잠금 중인 기록은 키가 가득 차도 내보내지 않는다(새 키를 대량으로 만들어 잠금을 풀지 못하게)
    const locked = (max: number) => (a: Attempts) => max > 0 && a.failures >= max;
    this.perClient = new BoundedExpiringStore(clock, 60_000, maxKeys, (a) => a.expiresAt, locked(config.maxFailedLogins));
    this.perUsername = new BoundedExpiringStore(clock, 60_000, maxKeys, (a) => a.expiresAt, locked(config.maxFailedLoginsPerUsername));
  }

  /** 시도 한 건을 실패로 미리 센다. 이미 잠겨 있으면 세지 않고 false */
  tryReserve(username: string, clientIp: string): boolean {
    const user = usernameKey(username);
    const client = clientKey(user, clientIp);
    if (!this.reserve(this.perClient, client, this.config.maxFailedLogins, '아이디·IP')) return false;
    if (!this.reserve(this.perUsername, user, this.config.maxFailedLoginsPerUsername, '아이디 전체')) {
      release(this.perClient, client); // 아이디 잠금으로 거부된 시도는 비밀번호 추측이 아니므로 되돌린다
      return false;
    }
    return true;
  }

  recordSuccess(username: string, clientIp: string): void {
    const user = usernameKey(username);
    this.perClient.delete(clientKey(user, clientIp));
    release(this.perUsername, user);
  }

  private reserve(store: BoundedExpiringStore<Attempts>, key: string, max: number, label: string): boolean {
    if (max <= 0) return true;
    const now = this.clock();
    let reserved = false;
    const updated = store.compute(key, (existing) => {
      if (!existing || now >= existing.expiresAt) {
        reserved = true;
        return { failures: 1, expiresAt: now + this.lockoutMs };
      }
      if (existing.failures >= max) return existing; // 잠김: 세지도, 잠금을 늘리지도 않는다
      reserved = true;
      return { failures: existing.failures + 1, expiresAt: now + this.lockoutMs };
    })!;
    if (reserved && updated.failures === max) {
      logger.warn(`로그인 잠금: ${label} 기준 실패 ${max}회, ${this.config.lockoutMinutes}분 동안 잠금`);
    }
    return reserved;
  }
}

function release(store: BoundedExpiringStore<Attempts>, key: string) {
  store.compute(key, (existing) =>
    !existing || existing.failures <= 1 ? undefined : { failures: existing.failures - 1, expiresAt: existing.expiresAt },
  );
}

/** 대소문자만 다른 아이디를 같은 키로 센다(Locale.ROOT 소문자) */
function usernameKey(username: string | null | undefined): string {
  const normalized = (username ?? '').trim().toLowerCase();
  return normalized.length > MAX_USERNAME_KEY_LENGTH ? normalized.slice(0, MAX_USERNAME_KEY_LENGTH) : normalized;
}

function clientKey(userKey: string, clientIp: string | null | undefined): string {
  return `${userKey}|${clientIp ?? ''}`;
}
