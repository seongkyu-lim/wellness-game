/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { cspPlugin } from './csp'
import { legalEnvPlugin } from './legalEnv'

export default defineConfig({
  plugins: [react(), cspPlugin(), legalEnvPlugin()],
  server: {
    port: 5173,
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/setupTests.ts'],
    restoreMocks: true,
    unstubGlobals: true,
    unstubEnvs: true,
    // 커버리지는 `npm run test:coverage`(--coverage)에서만 수집한다. 임계값도 그때만 검사된다.
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov'],
      reportsDirectory: './coverage',
      include: ['src/**/*.{ts,tsx}', 'csp.ts', 'legalEnv.ts'],
      exclude: ['**/*.test.{ts,tsx}', 'src/test/**', 'src/setupTests.ts', 'src/main.tsx', 'src/**/*.d.ts'],
      // 달성치(#154 기준 구문 98·분기 95·함수 98·줄 98%)보다 조금 낮게 둔다. 내려가면 test:coverage가 실패한다.
      thresholds: {
        statements: 96,
        branches: 93,
        functions: 96,
        lines: 97,
      },
    },
  },
})
