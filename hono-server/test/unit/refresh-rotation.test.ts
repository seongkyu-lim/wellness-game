import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { NewRefreshToken, RefreshTokenRow } from '../../src/auth/repositories.js';
import { Instant } from '../../src/lib/instant.js';

/**
 * 회전 리프레시 토큰 규칙 단위 테스트. DB 대신 메모리 저장소로 repositories 를 바꿔 끼운다.
 * (실제 SQL·행 잠금은 test/api 의 PostgreSQL 테스트가 본다)
 */
const rows: RefreshTokenRow[] = [];
const authState = new Map<string, { all: Instant; read: Instant | null }>();
const calls: string[] = [];

vi.mock('../../src/db/client.js', () => ({
  transaction: async (db: unknown, fn: (tx: unknown) => Promise<unknown>) => fn(db),
}));

vi.mock('../../src/auth/repositories.js', () => ({
  NO_CUTOFF: '1970-01-01T00:00:00Z',
  NO_CUTOFF_EPOCH_SECONDS: 0,
  refreshTokens: {
    insert: async (_db: unknown, t: NewRefreshToken) => {
      rows.push({
        id: t.id,
        user_id: t.userId,
        family_id: t.familyId,
        family_created_at: t.familyCreatedAt,
        token_hash: t.tokenHash,
        issued_at: t.issuedAt,
        expires_at: t.expiresAt,
        revoked_at: null,
        replaced_by: null,
        scope: t.scope,
        grace_reissued: false,
      });
    },
    findByHash: async (_db: unknown, hash: string) => rows.find((r) => r.token_hash === hash),
    findForUpdateByHash: async (_db: unknown, hash: string) => rows.find((r) => r.token_hash === hash),
    lockFamily: async (_db: unknown, familyId: string) => {
      calls.push(`lock ${familyId}`);
    },
    existsLiveInFamily: async (_db: unknown, familyId: string, now: Instant) =>
      rows.some((r) => r.family_id === familyId && r.revoked_at === null && r.expires_at.isAfter(now)),
    markRotated: async (_db: unknown, id: string, now: Instant, successor: string) => {
      const row = rows.find((r) => r.id === id)!;
      row.revoked_at = now;
      row.replaced_by = successor;
    },
    markGraceReissued: async (_db: unknown, id: string) => {
      rows.find((r) => r.id === id)!.grace_reissued = true;
    },
    revokeFamily: async (_db: unknown, familyId: string, now: Instant) => {
      let n = 0;
      for (const r of rows) if (r.family_id === familyId && r.revoked_at === null) (r.revoked_at = now), n++;
      return n;
    },
    revokeAllForUser: async (_db: unknown, userId: string, now: Instant) => {
      let n = 0;
      for (const r of rows) if (r.user_id === userId && r.revoked_at === null) (r.revoked_at = now), n++;
      return n;
    },
    deleteExpiredOrRevokedBefore: async () => 0,
  },
  userAuthStates: {
    find: async (_db: unknown, userId: string) => {
      const s = authState.get(userId);
      return s ? { tokens_valid_after: s.all, read_tokens_valid_after: s.read } : undefined;
    },
    advanceAll: async (_db: unknown, userId: string, at: Instant) => {
      const s = authState.get(userId);
      authState.set(userId, { all: s && s.all.isAfter(at) ? s.all : at, read: s?.read ?? null });
    },
    advanceRead: async (_db: unknown, userId: string, at: Instant) => {
      const s = authState.get(userId) ?? { all: Instant.ofEpochSecond(0), read: null };
      authState.set(userId, { all: s.all, read: s.read && s.read.isAfter(at) ? s.read : at });
    },
  },
}));

const { RefreshTokenService } = await import('../../src/auth/refresh-token.js');
const { TokenValidityService } = await import('../../src/auth/token-validity.js');
const { ApiError } = await import('../../src/lib/errors.js');
const { MutableClock, testConfig } = await import('../support/fixtures.js');

const db = {} as never;

async function code(promise: Promise<unknown>) {
  try {
    await promise;
    return 'ok';
  } catch (error) {
    if (error instanceof ApiError) return error.localized.code;
    throw error;
  }
}

describe('RefreshTokenService', () => {
  let clock: InstanceType<typeof MutableClock>;
  let validity: InstanceType<typeof TokenValidityService>;
  let service: InstanceType<typeof RefreshTokenService>;
  const config = testConfig().refresh;

  beforeEach(() => {
    rows.length = 0;
    authState.clear();
    calls.length = 0;
    clock = new MutableClock();
    validity = new TokenValidityService(clock.fn, 1000);
    service = new RefreshTokenService(config, validity, clock.fn);
  });

  it('원문은 43자 base64url, DB 에는 SHA-256 hex 만', async () => {
    const issued = await service.issue(db, 'u');
    expect(issued.refreshToken).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(issued.expiresIn).toBe(30 * 86400);
    expect(rows[0]!.token_hash).toMatch(/^[0-9a-f]{64}$/);
    expect(rows[0]!.token_hash).not.toBe(issued.refreshToken);
  });

  it('회전: 같은 family·scope 로 새 토큰, 이전 토큰은 replaced_by 와 함께 폐기, family 를 잠근다', async () => {
    const first = await service.issue(db, 'u', 'read');
    clock.advance(1000);
    const rotation = await service.rotate(db, first.refreshToken);
    expect(rotation).toMatchObject({ userId: 'u', scope: 'read' });
    expect(rows).toHaveLength(2);
    expect(rows[0]!.replaced_by).toBe(rows[1]!.id);
    expect(rows[1]!.family_id).toBe(rows[0]!.family_id);
    expect(calls).toEqual([`lock ${rows[0]!.family_id}`]);
  });

  it('read family 는 유휴 7일·절대 30일', async () => {
    const first = await service.issue(db, 'u', 'read');
    expect(first.expiresIn).toBe(7 * 86400);
    let token = first.refreshToken;
    for (let day = 0; day < 4; day++) {
      clock.advance(6 * 86400_000);
      token = (await service.rotate(db, token)).refreshToken.refreshToken;
    }
    // 24일 경과: 남은 절대 수명 6일이 유휴 7일보다 짧다
    clock.advance(1);
    const last = await service.rotate(db, token);
    expect(last.refreshToken.expiresIn).toBe(6 * 86400 - 1);
  });

  it('유예(30초) 안 재사용은 형제 토큰 1회, 두 번째는 재사용으로 보고 family 폐기 + 액세스 토큰 무효화', async () => {
    const first = await service.issue(db, 'u');
    await service.rotate(db, first.refreshToken);
    clock.advance(29_000);
    expect(await code(service.rotate(db, first.refreshToken))).toBe('ok');
    expect(rows[0]!.grace_reissued).toBe(true);
    expect(await code(service.rotate(db, first.refreshToken))).toBe('auth.refresh.reused');
    expect(rows.every((r) => r.revoked_at !== null)).toBe(true);
    expect(authState.get('u')!.all.epochSecond).toBe(Math.floor(clock.now / 1000));
  });

  it('유예가 지난 재사용 → reused, 로그아웃된 토큰 → invalid, 만료 → expired', async () => {
    const a = await service.issue(db, 'u');
    await service.rotate(db, a.refreshToken);
    clock.advance(31_000);
    expect(await code(service.rotate(db, a.refreshToken))).toBe('auth.refresh.reused');

    const b = await service.issue(db, 'v');
    await service.revokeFamily(db, b.refreshToken);
    expect(await code(service.rotate(db, b.refreshToken))).toBe('auth.refresh.invalid');

    const c = await service.issue(db, 'w');
    clock.advance(30 * 86400_000);
    expect(await code(service.rotate(db, c.refreshToken))).toBe('auth.refresh.expired');
    expect(await code(service.rotate(db, 'unknown'))).toBe('auth.refresh.invalid');
  });

  it('로그아웃: full family 만 조회 전용 기준을 옮긴다(웹 read family 는 옮기지 않음)', async () => {
    const web = await service.issue(db, 'u', 'read');
    await service.revokeFamily(db, web.refreshToken);
    expect(authState.get('u')).toBeUndefined();

    const app = await service.issue(db, 'u', 'full');
    await service.revokeFamily(db, app.refreshToken);
    expect(authState.get('u')!.all.epochSecond).toBe(0);
    expect(authState.get('u')!.read!.epochSecond).toBe(Math.floor(clock.now / 1000));
  });

  it('조회 전용 토큰은 !(iat > 기준)이면 거부(같은 초도 거부), 전체 기준은 iat < 기준만 거부', async () => {
    const app = await service.issue(db, 'u', 'full');
    await service.revokeFamily(db, app.refreshToken);
    const nowSec = Math.floor(clock.now / 1000);
    expect(await validity.isRevoked(db, 'u', nowSec, 'read')).toBe(true);
    expect(await validity.isRevoked(db, 'u', nowSec + 1, 'read')).toBe(false);
    expect(await validity.isRevoked(db, 'u', nowSec - 100, 'full')).toBe(false); // EPOCH = 기준 없음

    await service.revokeAll(db, 'u');
    expect(await validity.isRevoked(db, 'u', nowSec, 'full')).toBe(false);
    expect(await validity.isRevoked(db, 'u', nowSec - 1, 'full')).toBe(true);
  });
});
