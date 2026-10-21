import type { Db } from '../db/client.js';
import { BoundedExpiringStore } from '../lib/bounded-store.js';
import { Instant } from '../lib/instant.js';
import type { TokenScope } from './jwt.js';
import { userAuthStates, NO_CUTOFF_EPOCH_SECONDS } from './repositories.js';

export const CACHE_TTL_MS = 30_000;

interface CachedState {
  /** 모든 범위 토큰 기준(epoch 초). null 이면 없음(Instant.EPOCH 도 "없음") */
  validAfter: number | null;
  /** 조회 전용 토큰 기준(epoch 초). null 이면 없음 */
  readValidAfter: number | null;
  expiresAt: number;
}

/**
 * 사용자별 액세스 토큰 무효화 기준(Spring TokenValidityService, #103 반영).
 * - tokens_valid_after: 모든 기기 로그아웃·리프레시 재사용 감지 때 옮긴다. iat < 기준이면 범위와 무관하게 거부
 *   (JWT iat 는 초 단위라 기준도 초로 내린다. 같은 초에 발급된 토큰은 통과 — 직후 재로그인 토큰이 막히지 않게)
 * - read_tokens_valid_after: 전체 권한 family 의 이 기기 로그아웃 때 옮긴다. 조회 전용 토큰은 !(iat > 기준)이면 거부
 *   (초 단위 비교, 로그아웃과 같은 초에 발급된 조회 토큰도 거부)
 * - tokens_valid_after = 1970-01-01T00:00:00Z(Instant.EPOCH)는 "기준 없음"이다(조회 기준만 만들 때 넣는 값)
 * - 요청마다 DB 를 읽지 않게 30초 캐시. 다른 인스턴스(Workers isolate)는 최대 30초 늦게 반영한다.
 *   캐시를 채울 때 아직 유효한 기존 캐시와 필드별로 더 늦은 기준을 남긴다(#103 경쟁 수정)
 */
export class TokenValidityService {
  private readonly cache: BoundedExpiringStore<CachedState>;

  constructor(
    private readonly clock: () => number,
    maxKeys: number,
  ) {
    this.cache = new BoundedExpiringStore(clock, 60_000, Math.max(1, maxKeys), (s) => s.expiresAt);
  }

  /** 이 토큰이 무효화 기준에 걸리면 true(거부) */
  async isRevoked(db: Db, userId: string, issuedAt: number | null, scope: TokenScope): Promise<boolean> {
    const state = await this.state(db, userId);
    if (state.validAfter !== null && (issuedAt === null || issuedAt < state.validAfter)) return true;
    if (scope === 'read' && state.readValidAfter !== null) {
      return issuedAt === null || !(Math.floor(issuedAt) > Math.floor(state.readValidAfter));
    }
    return false;
  }

  /**
   * 지금까지 발급된 이 사용자의 액세스 토큰을 모두 무효로 만든다. 호출자의 트랜잭션에서 실행하고, 돌려준 함수를
   * 커밋한 뒤에 불러 캐시에 반영한다(Spring afterCommit: 롤백된 기준이 캐시에 남지 않게)
   */
  async revokeIssuedTokens(db: Db, userId: string): Promise<() => void> {
    const now = this.truncatedNow();
    await userAuthStates.advanceAll(db, userId, now);
    return this.cacheAfterCommit(db, userId, now.epochSecond, null);
  }

  /** 지금까지 발급된 이 사용자의 조회 전용 토큰(Watch·웹)만 무효로 만든다(#103). 커밋 뒤 돌려준 함수를 부른다 */
  async revokeReadTokens(db: Db, userId: string): Promise<() => void> {
    const now = this.truncatedNow();
    await userAuthStates.advanceRead(db, userId, now);
    return this.cacheAfterCommit(db, userId, null, now.epochSecond);
  }

  private truncatedNow(): Instant {
    return Instant.ofEpochSecond(Math.floor(this.clock() / 1000));
  }

  /** 트랜잭션 안의 읽기라 방금 쓴 값을 본다. 행이 안 보이면 방금 옮긴 값을 쓴다. 캐시 반영은 커밋 뒤 */
  private async cacheAfterCommit(db: Db, userId: string, advancedAll: number | null, advancedRead: number | null) {
    const row = await userAuthStates.find(db, userId);
    if (!row) return () => void this.merge(userId, advancedAll, advancedRead);
    const all = allCutoff(row.tokens_valid_after);
    const read = row.read_tokens_valid_after ? seconds(row.read_tokens_valid_after) : null;
    return () => void this.merge(userId, all, read);
  }

  private async state(db: Db, userId: string): Promise<CachedState> {
    const cached = this.cache.get(userId);
    if (cached && this.clock() < cached.expiresAt) return cached;
    const row = await userAuthStates.find(db, userId);
    return this.merge(
      userId,
      row ? allCutoff(row.tokens_valid_after) : null,
      row?.read_tokens_valid_after ? seconds(row.read_tokens_valid_after) : null,
    );
  }

  /** 아직 유효한 기존 캐시 값과 비교해 필드별로 더 늦은 시각을 남긴다. 만료된 기존 값은 버린다 */
  private merge(userId: string, validAfter: number | null, readValidAfter: number | null): CachedState {
    const now = this.clock();
    const expiresAt = now + CACHE_TTL_MS;
    return this.cache.compute(userId, (existing) =>
      !existing || now >= existing.expiresAt
        ? { validAfter, readValidAfter, expiresAt }
        : {
            validAfter: later(existing.validAfter, validAfter),
            readValidAfter: later(existing.readValidAfter, readValidAfter),
            expiresAt,
          },
    )!;
  }
}

function seconds(instant: Instant): number {
  return instant.epochSecond + instant.nano / 1e9;
}

function allCutoff(value: Instant): number | null {
  return value.epochSecond === NO_CUTOFF_EPOCH_SECONDS && value.nano === 0 ? null : seconds(value);
}

function later(a: number | null, b: number | null): number | null {
  if (a === null) return b;
  if (b === null) return a;
  return Math.max(a, b);
}
