import postgres from 'postgres';
import { Instant, parsePgTimestamptz } from '../lib/instant.js';

/** 쿼리 실행 객체. 루트 풀과 트랜잭션(sql.begin 안의 tx) 모두 이 타입으로 다룬다. */
export type Db = postgres.Sql<{}>;

export interface DbOptions {
  max?: number;
  /** Workers(Hyperdrive)에서는 요청마다 만들고 버리므로 유휴 연결을 오래 두지 않는다 */
  idleTimeoutSeconds?: number;
}

/**
 * postgres.js 클라이언트. 세션 TimeZone 을 UTC 로 고정하고,
 * - timestamptz → Instant(마이크로초 보존, Instant.toString 이 Spring 과 같은 문자열을 만든다)
 * - date → 'yyyy-MM-dd' 문자열
 * - numeric → number
 * 로 읽는다. 파라미터는 Instant.toString()·날짜 문자열을 넘기고 SQL 에서 ::timestamptz / ::date 로 캐스팅한다.
 */
export function createDb(url: string, options: DbOptions = {}): Db {
  return postgres(url, {
    max: options.max ?? 5,
    idle_timeout: options.idleTimeoutSeconds ?? 30,
    connect_timeout: 10,
    // 배열·사용자 정의 타입을 쓰지 않으므로 연결마다 pg_type 을 조회하지 않는다(Hyperdrive 왕복 절약)
    fetch_types: false,
    connection: { TimeZone: 'UTC', application_name: 'wellness-game-hono' },
    onnotice: () => {},
    types: {
      instant: {
        to: 1184,
        from: [1184],
        serialize: (x: Instant) => x.toString(),
        parse: (x: string) => parsePgTimestamptz(x),
      },
      localDate: {
        to: 1082,
        from: [1082],
        serialize: (x: string) => x,
        parse: (x: string) => x,
      },
      numeric: {
        to: 1700,
        from: [1700],
        serialize: (x: number) => String(x),
        parse: (x: string) => Number(x),
      },
    },
  }) as unknown as Db;
}

/** PostgreSQL 오류 코드(예: 23505 unique_violation) */
export function pgErrorCode(error: unknown): string | undefined {
  return typeof error === 'object' && error !== null && 'code' in error ? String((error as { code: unknown }).code) : undefined;
}

/** 트랜잭션(READ COMMITTED). fn 이 던지면 롤백한다 */
export async function transaction<T>(db: Db, fn: (tx: Db) => Promise<T>): Promise<T> {
  return (await db.begin((tx) => fn(tx as unknown as Db))) as T;
}
