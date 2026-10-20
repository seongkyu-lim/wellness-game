import { importJWK, type JWK } from 'jose';
import { logger } from '../lib/logger.js';
import type { HttpClient } from './http-client.js';

export const MIN_REFRESH_INTERVAL_MS = 60_000;
export const FAILURE_BACKOFF_MS = 30_000;

/** JWKS 를 받지 못함(호출자가 503 으로 바꾼다) */
export class JwksUnavailableError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = 'JwksUnavailableError';
  }
}

/** 서명 키를 고를 수 없는 토큰(alg·kid 문제). 호출자가 401 invalid 로 바꾼다 */
export class JwksKeyError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'JwksKeyError';
  }
}

type KeyMap = Map<string, CryptoKey>;

interface CachedKeys {
  keys: KeyMap;
  fetchedAt: number;
}

/**
 * 외부 ID 토큰(Apple identity token, Google ID token) 서명 검증용 RS256 공개키 캐시(Spring JwksKeyCache).
 * - 헤더 alg 가 RS256 이 아니거나 kid 가 없으면 거부
 * - cacheTtl 동안 캐시. 모르는 kid(키 교체)면 다시 받되 1분 안에서는 재조회하지 않는다(위조 kid 로 반복 호출 방지)
 * - 받기에 실패하면 30초 동안 재시도하지 않고 기존 키를 계속 쓴다. 마지막 조회가 staleMax 보다 오래됐거나
 *   키가 하나도 없으면 JwksUnavailableError(503)
 * - 재조회는 한 번만 진행한다. 기존 키가 있으면 다른 요청은 기다리지 않고 기존 키로 검증한다
 */
export class JwksKeyCache {
  private cached: CachedKeys | null = null;
  private retryNotBefore = 0;
  private inflight: Promise<KeyMap> | null = null;

  constructor(
    private readonly name: string,
    private readonly http: HttpClient,
    private readonly jwksUri: string,
    private readonly cacheTtlMs: number,
    private readonly staleMaxMs: number,
    private readonly clock: () => number,
  ) {}

  /** jose jwtVerify 의 키 함수 */
  readonly keyFor = async (header: { alg?: string; kid?: string }): Promise<CryptoKey> => {
    if (header.alg !== 'RS256') throw new JwksKeyError(`Unsupported ${this.name} token algorithm`);
    const kid = header.kid;
    if (!kid || !kid.trim()) throw new JwksKeyError(`Missing ${this.name} key id`);
    let key = (await this.keysFresherThan(this.cacheTtlMs)).get(kid);
    key ??= (await this.keysFresherThan(MIN_REFRESH_INTERVAL_MS)).get(kid);
    if (!key) throw new JwksKeyError(`Unknown ${this.name} key id`);
    return key;
  };

  private async keysFresherThan(maxAgeMs: number): Promise<KeyMap> {
    const cached = this.cached;
    if (cached && this.clock() < cached.fetchedAt + maxAgeMs) return cached.keys;
    return this.refresh(cached);
  }

  private async refresh(stale: CachedKeys | null): Promise<KeyMap> {
    if (this.clock() < this.retryNotBefore) return this.staleOrUnavailable(stale, null);
    if (this.inflight) {
      // 다른 요청이 받는 중: 기존 키가 있으면 기다리지 않는다(최초 조회 때만 대기)
      return stale ? this.staleOrUnavailable(stale, null) : this.inflight;
    }
    this.inflight = (async () => {
      try {
        const fetched = await this.fetchKeys();
        this.cached = { keys: fetched, fetchedAt: this.clock() };
        return fetched;
      } catch (error) {
        const failure =
          error instanceof JwksUnavailableError ? error : new JwksUnavailableError(`${this.name} JWKS 조회 실패`, { cause: error });
        this.retryNotBefore = this.clock() + FAILURE_BACKOFF_MS;
        logger.warn(
          `${this.name} JWKS 조회 실패. ${FAILURE_BACKOFF_MS / 1000}초 동안 재시도하지 않고 기존 키(${stale ? `${stale.keys.size}개` : '없음'})를 사용합니다.`,
        );
        return this.staleOrUnavailable(stale, failure);
      } finally {
        this.inflight = null;
      }
    })();
    return this.inflight;
  }

  private staleOrUnavailable(stale: CachedKeys | null, failure: JwksUnavailableError | null): KeyMap {
    if (stale && this.clock() <= stale.fetchedAt + this.staleMaxMs) return stale.keys;
    if (failure) throw failure;
    throw new JwksUnavailableError(stale ? `${this.name} JWKS 가 stale-max 보다 오래됨` : `${this.name} JWKS 재시도 대기 중`);
  }

  private async fetchKeys(): Promise<KeyMap> {
    const body = await this.http.getText(this.jwksUri);
    let parsed: unknown;
    try {
      parsed = JSON.parse(body);
    } catch (error) {
      throw new JwksUnavailableError(`${this.name} JWKS 형식 오류`, { cause: error });
    }
    const list = (parsed as { keys?: unknown }).keys;
    if (!Array.isArray(list)) throw new JwksUnavailableError(`${this.name} JWKS 형식 오류`);
    const keys: KeyMap = new Map();
    for (const jwk of list as JWK[]) {
      if (!jwk || jwk.kty !== 'RSA' || !jwk.kid) continue;
      try {
        const key = await importJWK({ ...jwk, alg: 'RS256' }, 'RS256');
        if (key instanceof Uint8Array) continue;
        keys.set(jwk.kid, key as CryptoKey);
      } catch (error) {
        throw new JwksUnavailableError(`${this.name} JWKS 키 해석 실패`, { cause: error });
      }
    }
    if (keys.size === 0) throw new JwksUnavailableError(`${this.name} JWKS 에 RSA 키가 없습니다.`);
    return keys;
  }
}
