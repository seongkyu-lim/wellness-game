import type { Context } from 'hono';
import type { AccessTokenPrincipal } from '../auth/jwt.js';
import type { Db } from '../db/client.js';
import { unauthorized } from '../lib/errors.js';
import type { Locale } from '../lib/i18n.js';
import type { Services } from '../services.js';

export interface AppVariables {
  services: Services;
  locale: Locale;
  clientIp: string;
  /** 인증 미들웨어가 검증한 토큰 주체(보호 경로에서만 있음) */
  principal?: AccessTokenPrincipal;
}

export interface AppEnv {
  Variables: AppVariables;
  // Workers 바인딩 또는 @hono/node-server 의 { incoming, outgoing }
  Bindings: Record<string, unknown>;
}

export type AppContext = Context<AppEnv>;

/** 런타임별 차이(DB 연결, 소켓 주소) */
export interface Platform {
  name: 'node' | 'workers';
  /** 이 요청에서 쓸 DB. Workers 는 요청마다 만들고 응답 뒤 닫는다 */
  db(c: AppContext): Db;
  /** Node 소켓 주소(Workers 는 CF-Connecting-IP 를 쓰므로 없어도 된다) */
  remoteAddress?(c: AppContext): string | undefined;
  /** X-Forwarded-For 헤더 줄들(원문). Node 는 같은 헤더 여러 줄을 쉼표로 합치므로 원시 헤더에서 읽는다 */
  forwardedForLines?(c: AppContext): string[];
}

/** 인증 미들웨어를 거친 보호 경로에서 토큰 주체(userId) */
export function authenticatedUserId(c: AppContext): string {
  const principal = c.get('principal');
  if (!principal || !principal.userId.trim()) throw unauthorized('auth.login-required');
  return principal.userId;
}
