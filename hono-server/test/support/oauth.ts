import { SignJWT, exportJWK, generateKeyPair, type JWK } from 'jose';
import type { FetchFn } from '../../src/auth/http-client.js';

export interface FetchCall {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: string;
}

type Handler = Response | ((call: FetchCall) => Response | Promise<Response>) | Error;

/** URL(전체 일치) → 응답/함수/Error 를 돌려주는 가짜 fetch. 호출 기록을 calls 에 쌓는다 */
const bodies = new WeakMap<Response, string>();

export function stubFetch(routes: Record<string, Handler>) {
  const calls: FetchCall[] = [];
  const fn: FetchFn = async (url, init) => {
    const call: FetchCall = {
      url,
      method: init?.method ?? 'GET',
      headers: (init?.headers ?? {}) as Record<string, string>,
      body: typeof init?.body === 'string' ? init.body : '',
    };
    calls.push(call);
    const handler = routes[url];
    if (handler === undefined) return new Response('not found', { status: 404 });
    if (handler instanceof Error) throw handler;
    if (typeof handler === 'function') return handler(call);
    // Response 는 본문을 한 번만 읽을 수 있어 매번 새로 만든다(clone 은 tee 라서 body.cancel() 이 영원히 끝나지 않는다)
    let text = bodies.get(handler);
    if (text === undefined) {
      text = await handler.clone().text();
      bodies.set(handler, text);
    }
    return new Response(text, { status: handler.status, headers: handler.headers });
  };
  return { fn, calls, routes };
}

export const json = (value: unknown, status = 200) => Response.json(value, { status });

export interface TestKey {
  kid: string;
  privateKey: CryptoKey;
  publicJwk: JWK;
}

/** 테스트용 RSA 키 한 쌍(RS256). kid 로 JWKS 에 실린다 */
export async function makeKey(kid: string): Promise<TestKey> {
  const { publicKey, privateKey } = await generateKeyPair('RS256', { extractable: true });
  const jwk = await exportJWK(publicKey);
  return { kid, privateKey, publicJwk: { ...jwk, kid, alg: 'RS256', use: 'sig' } };
}

export const jwksOf = (...keys: TestKey[]) => ({ keys: keys.map((k) => k.publicJwk) });

export interface IdTokenOptions {
  kid?: string;
  iss?: string | null;
  aud?: string | string[] | null;
  sub?: string | null;
  /** epoch 초 */
  iat: number;
  exp?: number | null;
  claims?: Record<string, unknown>;
  alg?: string;
}

/** 외부 공급자(Apple·Google)가 서명한 것처럼 ID 토큰을 만든다 */
export async function signIdToken(key: TestKey, options: IdTokenOptions): Promise<string> {
  const jwt = new SignJWT({ ...(options.claims ?? {}) }).setProtectedHeader({ alg: options.alg ?? 'RS256', kid: options.kid ?? key.kid });
  if (options.iss !== null) jwt.setIssuer(options.iss ?? 'https://accounts.google.com');
  if (options.aud !== null && options.aud !== undefined) jwt.setAudience(options.aud);
  if (options.sub !== null) jwt.setSubject(options.sub ?? 'sub-1');
  jwt.setIssuedAt(options.iat);
  if (options.exp !== null) jwt.setExpirationTime(options.exp ?? options.iat + 600);
  return jwt.sign(key.privateKey);
}

/** 기대하는 ApiError(상태 코드 + 메시지 코드) 단언용 */
export const apiError = (status: number, code: string) => ({ name: 'ApiError', status, localized: { code } });
