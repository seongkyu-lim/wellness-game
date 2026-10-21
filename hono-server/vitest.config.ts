import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['test/**/*.test.ts'],
    // API 테스트는 같은 DB 를 쓰므로 파일을 순서대로 실행한다
    fileParallelism: false,
    testTimeout: 20_000,
    hookTimeout: 60_000,
  },
});
