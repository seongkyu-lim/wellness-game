import { loadConfig, type AppConfig } from '../../src/config.js';

/** 테스트 전용 JWT 시크릿(실제 값 아님) */
export const TEST_SECRET = 'test-only-jwt-secret-0123456789-abcdefghijklmnop';

/** 2026-10-20T03:00:00Z = 서울 12:00 */
export const T0 = Date.parse('2026-10-20T03:00:00Z');

export function testConfig(overrides: Record<string, string> = {}): AppConfig {
  return loadConfig({
    AUTH_JWT_SECRET: TEST_SECRET,
    DATABASE_URL: process.env.DATABASE_URL_TEST ?? 'postgres://test:test@127.0.0.1:1/none',
    OAUTH_GOOGLE_CLIENT_ID: 'web-client.apps.googleusercontent.com',
    OAUTH_GOOGLE_CLIENT_SECRET: 'test-secret',
    OAUTH_GOOGLE_NATIVE_CLIENT_IDS: 'ios-client.apps.googleusercontent.com,android-server.apps.googleusercontent.com',
    OAUTH_APPLE_BUNDLE_ID: 'com.example.wellness',
    OAUTH_KAKAO_CLIENT_ID: 'kakao-client',
    OAUTH_KAKAO_NATIVE_APP_ID: '12345',
    OAUTH_NAVER_CLIENT_ID: 'naver-client',
    OAUTH_NAVER_CLIENT_SECRET: 'naver-secret',
    CORS_ALLOWED_ORIGINS: 'http://localhost:5173, https://app.example.com',
    APP_API_DOCS_ENABLED: 'true',
    ...overrides,
  });
}

/** 테스트에서 앞으로 돌릴 수 있는 시계 */
export class MutableClock {
  constructor(public now: number = T0) {}
  readonly fn = () => this.now;
  advance(ms: number) {
    this.now += ms;
  }
}
