import type { Db } from '../db/client.js';
import type { Instant } from '../lib/instant.js';
import type { TokenScope } from './jwt.js';

/**
 * 인증 관련 SQL. 테이블·컬럼은 backend/db/schema.sql 과 같다.
 * refresh_token.scope / refresh_token.grace_reissued / user_auth_state.read_tokens_valid_after 는 Spring V5(#103·#105) 컬럼이다.
 */

export interface RefreshTokenRow {
  id: string;
  user_id: string;
  family_id: string;
  family_created_at: Instant;
  token_hash: string;
  issued_at: Instant;
  expires_at: Instant;
  revoked_at: Instant | null;
  replaced_by: string | null;
  scope: TokenScope;
  grace_reissued: boolean;
}

export interface NewRefreshToken {
  id: string;
  userId: string;
  familyId: string;
  familyCreatedAt: Instant;
  tokenHash: string;
  issuedAt: Instant;
  expiresAt: Instant;
  scope: TokenScope;
}

export const refreshTokens = {
  async insert(db: Db, t: NewRefreshToken): Promise<void> {
    await db`
      insert into refresh_token (id, user_id, family_id, family_created_at, token_hash, issued_at, expires_at, scope)
      values (${t.id}::uuid, ${t.userId}, ${t.familyId}::uuid, ${t.familyCreatedAt.toString()}::timestamptz, ${t.tokenHash},
              ${t.issuedAt.toString()}::timestamptz, ${t.expiresAt.toString()}::timestamptz, ${t.scope})`;
  },

  /** 회전용 조회. 행 잠금으로 같은 토큰의 동시 회전을 직렬화하고, 앞선 회전이 커밋한 최신 상태를 본다 */
  async findForUpdateByHash(db: Db, tokenHash: string): Promise<RefreshTokenRow | undefined> {
    const rows = await db<RefreshTokenRow[]>`
      select id::text, user_id, family_id::text, family_created_at, token_hash, issued_at, expires_at, revoked_at,
             replaced_by::text, scope, grace_reissued
      from refresh_token where token_hash = ${tokenHash} for update`;
    return rows[0];
  },

  async findByHash(db: Db, tokenHash: string): Promise<RefreshTokenRow | undefined> {
    const rows = await db<RefreshTokenRow[]>`
      select id::text, user_id, family_id::text, family_created_at, token_hash, issued_at, expires_at, revoked_at,
             replaced_by::text, scope, grace_reissued
      from refresh_token where token_hash = ${tokenHash}`;
    return rows[0];
  },

  /** family 의 모든 행을 잠근다(id 순서로 잠가 교착을 피한다). 회전·로그아웃이 같은 family 를 동시에 바꾸지 못하게 */
  async lockFamily(db: Db, familyId: string): Promise<void> {
    await db`select id from refresh_token where family_id = ${familyId}::uuid order by id for update`;
  },

  async existsLiveInFamily(db: Db, familyId: string, now: Instant): Promise<boolean> {
    const rows = await db`
      select 1 from refresh_token
      where family_id = ${familyId}::uuid and revoked_at is null and expires_at > ${now.toString()}::timestamptz
      limit 1`;
    return rows.length > 0;
  },

  /** 회전으로 폐기한다. replaced_by 는 첫 후계 토큰(유예 구간의 형제 토큰은 기록하지 않음) */
  async markRotated(db: Db, id: string, now: Instant, successorId: string): Promise<void> {
    await db`update refresh_token set revoked_at = ${now.toString()}::timestamptz, replaced_by = ${successorId}::uuid where id = ${id}::uuid`;
  },

  async markGraceReissued(db: Db, id: string): Promise<void> {
    await db`update refresh_token set grace_reissued = true where id = ${id}::uuid`;
  },

  async revokeFamily(db: Db, familyId: string, now: Instant): Promise<number> {
    const result = await db`
      update refresh_token set revoked_at = ${now.toString()}::timestamptz
      where family_id = ${familyId}::uuid and revoked_at is null`;
    return result.count;
  },

  async revokeAllForUser(db: Db, userId: string, now: Instant): Promise<number> {
    const result = await db`
      update refresh_token set revoked_at = ${now.toString()}::timestamptz where user_id = ${userId} and revoked_at is null`;
    return result.count;
  },

  async deleteExpiredOrRevokedBefore(db: Db, now: Instant, revokedBefore: Instant): Promise<number> {
    const result = await db`
      delete from refresh_token
      where expires_at <= ${now.toString()}::timestamptz or revoked_at <= ${revokedBefore.toString()}::timestamptz`;
    return result.count;
  },
};

export interface AuthStateRow {
  tokens_valid_after: Instant;
  read_tokens_valid_after: Instant | null;
}

/** 행이 없을 때 넣는 tokens_valid_after(NOT NULL) 값 = Instant.EPOCH. "전체 기준 없음"을 뜻한다(Spring NO_CUTOFF) */
export const NO_CUTOFF = '1970-01-01T00:00:00Z';
export const NO_CUTOFF_EPOCH_SECONDS = 0;

export const userAuthStates = {
  async find(db: Db, userId: string): Promise<AuthStateRow | undefined> {
    const rows = await db<AuthStateRow[]>`
      select tokens_valid_after, read_tokens_valid_after from user_auth_state where user_id = ${userId}`;
    return rows[0];
  },

  /**
   * 전체 토큰 기준을 at 으로(앞으로만) 옮긴다. Spring 은 insert ... on conflict do nothing 뒤 greatest() update 를 쓴다.
   * 같은 사용자의 첫 기록이 동시에 와도 PK 위반 없이 둘 다 성공한다.
   */
  async advanceAll(db: Db, userId: string, at: Instant): Promise<void> {
    await db`insert into user_auth_state (user_id, tokens_valid_after) values (${userId}, ${at.toString()}::timestamptz) on conflict do nothing`;
    await db`
      update user_auth_state set tokens_valid_after = greatest(tokens_valid_after, ${at.toString()}::timestamptz)
      where user_id = ${userId}`;
  },

  /** 조회 전용(read) 토큰 기준만 옮긴다(이 기기 로그아웃, #103) */
  async advanceRead(db: Db, userId: string, at: Instant): Promise<void> {
    await db`insert into user_auth_state (user_id, tokens_valid_after) values (${userId}, ${NO_CUTOFF}::timestamptz) on conflict do nothing`;
    await db`
      update user_auth_state
      set read_tokens_valid_after = greatest(coalesce(read_tokens_valid_after, ${at.toString()}::timestamptz), ${at.toString()}::timestamptz)
      where user_id = ${userId}`;
  },
};

export interface CredentialRow {
  username: string;
  password_hash: string;
  user_identifier: string;
  display_name: string | null;
}

export const userCredentials = {
  async findByUsername(db: Db, username: string): Promise<CredentialRow | undefined> {
    const rows = await db<CredentialRow[]>`
      select username, password_hash, user_identifier, display_name from user_credential where username = ${username}`;
    return rows[0];
  },

  /** 대소문자 무시 일치(최대 2건만 읽는다: 유일한지만 보면 된다) */
  async findByUsernameIgnoreCase(db: Db, username: string): Promise<CredentialRow[]> {
    return db<CredentialRow[]>`
      select username, password_hash, user_identifier, display_name from user_credential
      where lower(username) = lower(${username}) limit 2`;
  },

  async existsByUsernameIgnoreCase(db: Db, username: string): Promise<boolean> {
    const rows = await db`select 1 from user_credential where lower(username) = lower(${username}) limit 1`;
    return rows.length > 0;
  },

  async insert(
    db: Db,
    c: { id: string; username: string; passwordHash: string; userIdentifier: string; displayName: string; now: Instant },
  ): Promise<void> {
    await db`
      insert into user_credential (id, username, password_hash, user_identifier, display_name, created_at, updated_at)
      values (${c.id}::uuid, ${c.username}, ${c.passwordHash}, ${c.userIdentifier}, ${c.displayName},
              ${c.now.toString()}::timestamptz, ${c.now.toString()}::timestamptz)`;
  },
};
