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
  },
})
