import bcrypt from 'bcryptjs';
import type { AppConfig } from '../config.js';
import { pgErrorCode, type Db } from '../db/client.js';
import { randomUuid } from '../lib/crypto.js';
import { badRequest } from '../lib/errors.js';
import { Instant } from '../lib/instant.js';
import { LoginAttemptGuard } from './login-guard.js';
import { userCredentials, type CredentialRow } from './repositories.js';

/** Spring BCryptPasswordEncoder 기본 강도 */
export const BCRYPT_COST = 10;
export const USER_ID_PREFIX = 'password:';
/** 가입 아이디 규칙. 로그인 입력이 이 규칙에 맞지 않으면 DB 를 보지 않고 실패로 센다 */
export const USERNAME_PATTERN = /^[A-Za-z0-9_]{4,32}$/;

export interface PasswordAuthResult {
  userIdentifier: string;
  displayName: string | null;
  /** 다른 API 의 userId(토큰 주체) = "password:{userIdentifier}" */
  userId: string;
}

/**
 * 아이디·비밀번호 계정(Spring PasswordAuthService, #106 반영). BCrypt($2a/$2b, cost 10) 해시를 그대로 공유한다.
 * 주의: bcrypt 한 번이 수십 ms CPU 를 쓴다. Workers 무료 플랜(요청당 CPU 10ms)에서는 초과하므로
 * 아이디 로그인·가입은 Workers 유료 플랜이나 Node 런타임에서 운영한다(README).
 */
export class PasswordAuthService {
  private readonly guard: LoginAttemptGuard;
  private dummyHash: Promise<string> | null = null;

  constructor(
    lockout: AppConfig['lockout'],
    private readonly clock: () => number,
    maxKeys: number,
  ) {
    this.guard = new LoginAttemptGuard(lockout, clock, maxKeys);
  }

  /** 없는 아이디도 해시 비교 한 번을 거치게 해 응답 시간으로 아이디 존재가 드러나지 않게 한다 */
  private dummyPasswordHash(): Promise<string> {
    this.dummyHash ??= bcrypt.hash(randomUuid(), BCRYPT_COST);
    return this.dummyHash;
  }

  /** 아이디는 소문자로 정규화해 저장한다. 대소문자만 다른 아이디·동시 가입은 username-taken(400) */
  async signUp(db: Db, username: string, rawPassword: string, displayName: string | null | undefined): Promise<PasswordAuthResult> {
    const normalizedUsername = (username ?? '').trim().toLowerCase();
    if (await userCredentials.existsByUsernameIgnoreCase(db, normalizedUsername)) throw badRequest('auth.username-taken');
    const resolvedDisplayName = !displayName || !displayName.trim() ? normalizedUsername : displayName.trim();
    const userIdentifier = randomUuid();
    const passwordHash = await bcrypt.hash(rawPassword, BCRYPT_COST);
    try {
      await userCredentials.insert(db, {
        id: randomUuid(),
        username: normalizedUsername,
        passwordHash,
        userIdentifier,
        displayName: resolvedDisplayName,
        now: Instant.ofEpochMilli(this.clock()),
      });
    } catch (error) {
      if (pgErrorCode(error) === '23505') throw badRequest('auth.username-taken');
      throw error;
    }
    return { userIdentifier, displayName: resolvedDisplayName, userId: USER_ID_PREFIX + userIdentifier };
  }

  /**
   * 실패 사유(규칙에 안 맞는 아이디, 없는 아이디, 틀린 비밀번호, 잠금)와 상관없이 같은 400 문구로 응답하고,
   * 모든 경로에서 bcrypt 비교를 한 번 해 응답 시간도 비슷하게 맞춘다. 잠긴 동안은 올바른 비밀번호도 거부한다.
   * 잠금 키는 찾은 계정의 실제 아이디(없으면 입력)다: 대소문자를 바꿔 입력해도 같은 카운터를 쓴다.
   */
  async signIn(db: Db, username: string, rawPassword: string, clientIp: string): Promise<PasswordAuthResult> {
    const trimmed = (username ?? '').trim();
    // 가입 규칙에 안 맞는 아이디는 DB 를 보지 않고 실패(bcrypt 비교는 해서 응답 시간을 맞춘다)
    if (!USERNAME_PATTERN.test(trimmed)) return this.fail(rawPassword);
    const found = await findForLogin(db, trimmed);
    const key = found?.username ?? trimmed.toLowerCase();
    if (!this.guard.tryReserve(key, clientIp)) return this.fail(rawPassword);
    const matches = await bcrypt.compare(rawPassword, found?.password_hash ?? (await this.dummyPasswordHash()));
    if (!found || !matches) throw badRequest('auth.invalid-credentials');
    this.guard.recordSuccess(key, clientIp);
    return { userIdentifier: found.user_identifier, displayName: found.display_name, userId: USER_ID_PREFIX + found.user_identifier };
  }

  private async fail(rawPassword: string): Promise<never> {
    await bcrypt.compare(rawPassword, await this.dummyPasswordHash());
    throw badRequest('auth.invalid-credentials');
  }
}

/** 입력 그대로 → 소문자 → 대소문자 무시(정확히 한 건일 때만) 순서로 찾는다(정규화 이전에 가입한 계정 호환) */
async function findForLogin(db: Db, trimmed: string): Promise<CredentialRow | undefined> {
  const exact = await userCredentials.findByUsername(db, trimmed);
  if (exact) return exact;
  const lowered = trimmed.toLowerCase();
  if (lowered !== trimmed) {
    const lower = await userCredentials.findByUsername(db, lowered);
    if (lower) return lower;
  }
  const candidates = await userCredentials.findByUsernameIgnoreCase(db, trimmed);
  return candidates.length === 1 ? candidates[0] : undefined;
}
