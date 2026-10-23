import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['test/**/*.test.ts'],
    // API 테스트는 같은 DB 를 쓰므로 파일을 순서대로 실행한다
    fileParallelism: false,
    testTimeout: 20_000,
    hookTimeout: 60_000,
    coverage: {
      provider: 'v8',
      include: ['src/**/*.ts'],
      reporter: ['text', 'html', 'lcov'],
      reportsDirectory: 'coverage',
      // 임계값은 `npm run test:coverage`(--coverage)일 때만 적용된다. 평소 `npm test` 는 측정하지 않는다.
      // DB 테스트(DATABASE_URL_TEST)가 빠지면 라우트·동기화·리프레시 경로가 측정되지 않으므로 기준을 따로 둔다.
      // 값은 실측(DB 있음: 줄 97.6·분기 92.6·함수 95.8, DB 없음: 줄 80.2·분기 79.1·함수 73.0)보다 조금 낮게 잡았다.
      thresholds: process.env.DATABASE_URL_TEST
        ? { lines: 95, statements: 94, branches: 90, functions: 93 }
        : { lines: 78, statements: 77, branches: 76, functions: 70 },
    },
  },
});
