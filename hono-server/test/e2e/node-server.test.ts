import { spawn, type ChildProcess } from 'node:child_process';
import { request } from 'node:http';
import { createServer } from 'node:net';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { TEST_SECRET } from '../support/fixtures.js';

/**
 * Node 진입점(src/node.ts)을 실제 프로세스·소켓으로 띄운다. 소켓 주소(getConnInfo)와 X-Forwarded-For 원시 헤더 줄 같은
 * Node 어댑터 전용 동작은 app.request() 로는 확인할 수 없다. DB 는 쓰지 않는 경로(헬스·검증 오류)만 호출한다.
 */
async function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address() as { port: number };
      server.close(() => resolve(port));
    });
  });
}

/** 같은 이름 헤더를 여러 줄로 보낼 수 있는 저수준 요청(fetch 는 합쳐 버린다) */
function rawRequest(port: number, path: string, headers: Record<string, string | string[]> = {}, method = 'GET', body?: string) {
  return new Promise<{ status: number; body: string; headers: Record<string, string | string[] | undefined> }>((resolve, reject) => {
    const req = request({ host: '127.0.0.1', port, path, method, headers }, (res) => {
      let data = '';
      res.on('data', (chunk) => (data += chunk));
      res.on('end', () => resolve({ status: res.statusCode ?? 0, body: data, headers: res.headers }));
    });
    req.on('error', reject);
    if (body !== undefined) req.write(body);
    req.end();
  });
}

describe('Node 서버 프로세스(src/node.ts)', () => {
  let child: ChildProcess;
  let port: number;
  let output = '';
  let exit: Promise<number | null>;

  beforeAll(async () => {
    port = await freePort();
    child = spawn(process.execPath, ['--import', 'tsx', 'src/node.ts'], {
      cwd: new URL('../..', import.meta.url).pathname,
      env: {
        PATH: process.env.PATH,
        PORT: String(port),
        AUTH_JWT_SECRET: TEST_SECRET,
        DATABASE_URL: 'postgres://nobody:none@127.0.0.1:1/none',
        APP_RATE_LIMIT_LOGIN_PER_MINUTE: '1',
        APP_RATE_LIMIT_TRUST_FORWARDED_HEADER: 'true',
        APP_RATE_LIMIT_TRUSTED_PROXY_HOPS: '0',
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    child.stdout!.on('data', (d) => (output += d));
    child.stderr!.on('data', (d) => (output += d));
    exit = new Promise((resolve) => child.once('exit', (code) => resolve(code)));
    // 준비될 때까지 조건 대기(고정 시간 sleep 이 아니라 /healthz 응답이 올 때까지)
    const deadline = Date.now() + 30_000;
    for (;;) {
      try {
        if ((await rawRequest(port, '/healthz')).status === 200) break;
      } catch {
        // 아직 포트가 열리지 않음
      }
      if (child.exitCode !== null) throw new Error(`서버가 종료됨: ${output}`);
      if (Date.now() > deadline) throw new Error(`서버가 시간 안에 뜨지 않음: ${output}`);
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
  }, 40_000);

  afterAll(() => {
    if (child && child.exitCode === null) child.kill('SIGKILL');
  });

  it('헬스체크와 보안 헤더', async () => {
    const res = await rawRequest(port, '/healthz');
    expect(JSON.parse(res.body)).toEqual({ status: 'UP' });
    expect(res.headers['x-frame-options']).toBe('DENY');
    expect(res.headers['cache-control']).toBe('no-store');
  });

  it('신뢰하는 프록시가 덧붙인 X-Forwarded-For 마지막 값으로 로그인 한도를 센다', async () => {
    const login = (xff: string | string[]) =>
      rawRequest(port, '/api/auth/login', { 'Content-Type': 'application/json', 'X-Forwarded-For': xff }, 'POST', '{}');
    expect((await login('9.9.9.9, 10.1.1.1')).status).toBe(400);
    expect((await login('8.8.8.8, 10.1.1.1')).status).toBe(429); // 같은 마지막 값 → 같은 버킷
    expect((await login('9.9.9.9, 10.2.2.2')).status).toBe(400); // 마지막 값이 다르면 다른 버킷
  });

  it('X-Forwarded-For 가 여러 줄로 오면 모두 하나의 공용 버킷으로 센다(위조 줄로 키를 늘릴 수 없음)', async () => {
    const login = (lines: string[]) =>
      rawRequest(port, '/api/auth/login', { 'Content-Type': 'application/json', 'X-Forwarded-For': lines }, 'POST', '{}');
    expect((await login(['1.0.0.1', '1.0.0.2'])).status).toBe(400);
    expect((await login(['2.0.0.1', '2.0.0.2'])).status).toBe(429);
  });

  it('오류 응답 형식은 같은 앱과 같다(415·404)', async () => {
    const wrongType = await rawRequest(port, '/api/auth/refresh', { 'Content-Type': 'text/plain', 'X-Forwarded-For': '7.7.7.7' }, 'POST', 'x');
    expect(JSON.parse(wrongType.body)).toMatchObject({ status: 415, error: 'Unsupported Media Type' });
    const missing = await rawRequest(port, '/nope');
    expect(JSON.parse(missing.body)).toMatchObject({ status: 404, path: '/nope' });
  });

  it('SIGTERM 을 받으면 정상 종료(코드 0)한다', async () => {
    child.kill('SIGTERM');
    const code = await Promise.race([exit, new Promise<'timeout'>((resolve) => setTimeout(() => resolve('timeout'), 20_000))]);
    expect(code, output).toBe(0);
    expect(output).toContain('SIGTERM');
  }, 30_000);
});
