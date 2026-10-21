import { readFileSync } from 'node:fs';
import { describe } from 'vitest';
import { createApp } from '../../src/app.js';
import type { FetchFn } from '../../src/auth/http-client.js';
import { createDb, type Db } from '../../src/db/client.js';
import { createServices } from '../../src/services.js';
import { MutableClock, testConfig } from './fixtures.js';

/** DATABASE_URL_TEST 가 있을 때만 실행하는 describe(없으면 건너뜀) */
export const DATABASE_URL_TEST = process.env.DATABASE_URL_TEST;
export const describeDb = DATABASE_URL_TEST ? describe : describe.skip;

/** Spring 스키마(backend/db/schema.sql)를 빈 public 스키마에 만든다 */
export async function resetSchema(db: Db): Promise<void> {
  const schemaPath = process.env.SCHEMA_SQL ?? new URL('../../../backend/db/schema.sql', import.meta.url).pathname;
  await db.unsafe('drop schema if exists public cascade; create schema public;');
  await db.unsafe(readFileSync(schemaPath, 'utf8'));
}

export function buildTestApp(options: { db: Db; clock?: MutableClock; fetch?: FetchFn; env?: Record<string, string> }) {
  const clock = options.clock ?? new MutableClock();
  const services = createServices(testConfig(options.env), {
    clock: clock.fn,
    fetch: options.fetch ?? (async () => new Response('{}', { status: 500 })),
  });
  const app = createApp({
    services,
    platform: { name: 'node', db: () => options.db, remoteAddress: () => '203.0.113.10' },
  });
  const call = async (method: string, path: string, init: { body?: unknown; token?: string; headers?: Record<string, string> } = {}) => {
    const headers: Record<string, string> = { ...(init.headers ?? {}) };
    if (init.body !== undefined) headers['Content-Type'] ??= 'application/json';
    if (init.token) headers.Authorization = `Bearer ${init.token}`;
    const response = await app.request(path, {
      method,
      headers,
      body: init.body === undefined ? undefined : typeof init.body === 'string' ? init.body : JSON.stringify(init.body),
    });
    const text = await response.text();
    let json: any = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch {
      json = text;
    }
    return { status: response.status, headers: response.headers, json };
  };
  return { app, services, clock, call };
}

export function openDb(): Db {
  return createDb(DATABASE_URL_TEST!, { max: 4 });
}
