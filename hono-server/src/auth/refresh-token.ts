import type { AppConfig } from '../config.js';
import { transaction, type Db } from '../db/client.js';
import { randomBase64Url, randomUuid, sha256Hex } from '../lib/crypto.js';
import { ApiError, unauthorized } from '../lib/errors.js';
import { Instant } from '../lib/instant.js';
import { logger } from '../lib/logger.js';
import type { TokenScope } from './jwt.js';
import { refreshTokens, type RefreshTokenRow } from './repositories.js';
import type { TokenValidityService } from './token-validity.js';

export const TOKEN_BYTES = 32;

export interface IssuedRefreshToken {
  refreshToken: string;
  expiresIn: number;
}

export interface Rotation {
  userId: string;
  scope: TokenScope;
  refreshToken: IssuedRefreshToken;
}

/**
 * 회전 리프레시 토큰(Spring RefreshTokenService, Auth v2 #55 + #103·#105).
 * - 원문은 32바이트 난수의 base64url(패딩 없음), DB 에는 SHA-256 hex 만 저장
 * - 로그인마다 새 family. 회전하면 기존 토큰을 폐기하고 replaced_by 를 남긴 뒤 같은 family 로 새 토큰을 준다.
 *   만료 = min(지금 + ttl, family 생성 + absolute-ttl). 범위(scope)는 family 에 저장돼 회전 후에도 유지된다(#105)
 * - 조회 전용(read) family 는 유휴 7일·절대 30일(#105), 전체(full) family 는 30일·90일
 * - 회전된 지 reuse-grace(기본 30초) 안의 토큰이 다시 오면 형제 토큰을 한 번만 더 준다(grace_reissued).
 *   두 번째부터·유예 구간 이후는 탈취 후 재사용으로 보고 family 전체를 폐기하고 그 사용자의 액세스 토큰도 무효로 만든 뒤 401
 * - 로그아웃으로 폐기된 토큰은 그냥 401
 */
export class RefreshTokenService {
  constructor(
    private readonly config: AppConfig['refresh'],
    private readonly validity: TokenValidityService,
    private readonly clock: () => number = Date.now,
  ) {}

  /** 새 로그인: 새 family 의 첫 토큰 */
  async issue(db: Db, userId: string, scope: TokenScope = 'full'): Promise<IssuedRefreshToken> {
    if (!userId || !userId.trim()) throw new Error('토큰 주체(userId)가 비어 있습니다.');
    const now = this.now();
    const created = await this.create(db, userId, randomUuid(), now, now, scope);
    return created.issued;
  }

  /** 리프레시 토큰을 회전한다. 재사용 처리(폐기·무효화)는 커밋한 뒤 401 을 던진다 */
  async rotate(db: Db, rawToken: string): Promise<Rotation> {
    const hash = rawToken && rawToken.trim() ? await sha256Hex(rawToken.trim()) : null;
    const afterCommit: (() => void)[] = [];
    const outcome = await transaction(db, async (tx): Promise<Rotation | ApiError> => {
      const now = this.now();
      const found = hash ? await refreshTokens.findByHash(tx, hash) : undefined;
      if (!found) return invalid();
      // family 행을 모두 잠근 뒤(회전·로그아웃·유예 재발급 직렬화) 잠금 읽기로 최신 상태를 다시 본다
      await refreshTokens.lockFamily(tx, found.family_id);
      const current = await refreshTokens.findForUpdateByHash(tx, hash!);
      if (!current) return invalid();
      if (current.revoked_at !== null) return this.rotateRevoked(tx, current, now, afterCommit);
      if (!current.expires_at.isAfter(now)) return unauthorized('auth.refresh.expired');
      const next = await this.create(tx, current.user_id, current.family_id, current.family_created_at, now, current.scope);
      await refreshTokens.markRotated(tx, current.id, now, next.id);
      return { userId: current.user_id, scope: current.scope, refreshToken: next.issued };
    });
    afterCommit.forEach((fn) => fn());
    if (outcome instanceof ApiError) throw outcome;
    return outcome;
  }

  private async rotateRevoked(tx: Db, token: RefreshTokenRow, now: Instant, afterCommit: (() => void)[]): Promise<Rotation | ApiError> {
    if (token.replaced_by === null) return invalid(); // 로그아웃·재사용 감지로 폐기됨
    const graceEnd = Instant.ofEpochMilli(token.revoked_at!.toEpochMilli() + this.config.reuseGraceMs);
    const withinGrace = !now.isAfter(graceEnd);
    if (!withinGrace || token.grace_reissued) return this.reuseDetected(tx, token, now, afterCommit);
    if (!(await refreshTokens.existsLiveInFamily(tx, token.family_id, now))) {
      return invalid(); // 유예 구간 안이지만 family 가 이미 로그아웃됨
    }
    await refreshTokens.markGraceReissued(tx, token.id);
    const sibling = await this.create(tx, token.user_id, token.family_id, token.family_created_at, now, token.scope);
    logger.warn(`리프레시 토큰 유예 재발급: userId=${token.user_id}, family=${token.family_id}`);
    return { userId: token.user_id, scope: token.scope, refreshToken: sibling.issued };
  }

  private async reuseDetected(tx: Db, token: RefreshTokenRow, now: Instant, afterCommit: (() => void)[]): Promise<ApiError> {
    const revoked = await refreshTokens.revokeFamily(tx, token.family_id, now);
    afterCommit.push(await this.validity.revokeIssuedTokens(tx, token.user_id));
    logger.warn(`리프레시 토큰 재사용 감지: userId=${token.user_id}, family=${token.family_id}, 폐기 ${revoked}건`);
    return unauthorized('auth.refresh.reused');
  }

  /**
   * 이 기기 로그아웃(토큰 소유가 곧 권한): 그 토큰의 family 를 잠그고 폐기한다. 전체 권한(full) family 면 그 사용자의
   * 조회 전용 토큰(Watch·웹)도 무효로 만든다(#103). 웹 로그아웃(read family)은 조회 기준을 옮기지 않는다.
   * 없는 토큰이면 아무것도 하지 않는다.
   */
  async revokeFamily(db: Db, rawToken: string | null | undefined): Promise<void> {
    if (!rawToken || !rawToken.trim()) return;
    const hash = await sha256Hex(rawToken.trim());
    const applyCache = await transaction(db, async (tx) => {
      const token = await refreshTokens.findByHash(tx, hash);
      if (!token) return null;
      await refreshTokens.lockFamily(tx, token.family_id);
      await refreshTokens.revokeFamily(tx, token.family_id, this.now());
      return token.scope === 'full' ? this.validity.revokeReadTokens(tx, token.user_id) : null;
    });
    applyCache?.();
  }

  /** 모든 기기 로그아웃: 리프레시 토큰을 모두 폐기하고 이미 발급된 액세스 토큰도 무효로 만든다 */
  async revokeAll(db: Db, userId: string): Promise<void> {
    const applyCache = await transaction(db, async (tx) => {
      await refreshTokens.revokeAllForUser(tx, userId, this.now());
      return this.validity.revokeIssuedTokens(tx, userId);
    });
    applyCache();
  }

  /** 만료된 행과, 폐기된 지 revoked-retention 이 지난 행을 지운다 */
  async purgeExpiredAndRevoked(db: Db): Promise<number> {
    const now = this.now();
    return refreshTokens.deleteExpiredOrRevokedBefore(db, now, Instant.ofEpochMilli(now.toEpochMilli() - this.config.revokedRetentionMs));
  }

  private now(): Instant {
    return Instant.ofEpochMilli(this.clock());
  }

  private async create(
    db: Db,
    userId: string,
    familyId: string,
    familyCreatedAt: Instant,
    now: Instant,
    scope: TokenScope,
  ): Promise<{ id: string; issued: IssuedRefreshToken }> {
    const raw = randomBase64Url(TOKEN_BYTES);
    const ttl = scope === 'read' ? this.config.readTtlMs : this.config.ttlMs;
    const absoluteTtl = scope === 'read' ? this.config.readAbsoluteTtlMs : this.config.absoluteTtlMs;
    const expiresAtMs = Math.min(now.toEpochMilli() + ttl, familyCreatedAt.toEpochMilli() + absoluteTtl);
    const expiresAt = Instant.ofEpochMilli(expiresAtMs);
    const id = randomUuid();
    await refreshTokens.insert(db, {
      id,
      userId,
      familyId,
      familyCreatedAt,
      tokenHash: await sha256Hex(raw),
      issuedAt: now,
      expiresAt,
      scope,
    });
    return { id, issued: { refreshToken: raw, expiresIn: Math.trunc((expiresAtMs - now.toEpochMilli()) / 1000) } };
  }
}

function invalid(): ApiError {
  return unauthorized('auth.refresh.invalid');
}
