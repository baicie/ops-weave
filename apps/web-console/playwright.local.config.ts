import { defineConfig } from '@playwright/test'
import base from './playwright.config.ts'

// Isolated Vite development fixture, never the running local business preview.
export default defineConfig({
  ...base,
  testDir: './e2e-local',
  use: { ...base.use, baseURL: 'http://127.0.0.1:4175' },
  webServer: {
    command: 'pnpm dev --port 4175 --strictPort',
    port: 4175,
    reuseExistingServer: false,
    timeout: 120_000,
    env: {
      OPSWEAVE_WEB_LOCAL_TOKEN: 'fixture-local-platform-credential-never-production-123456789',
      HTTP_PROXY: '', HTTPS_PROXY: '', ALL_PROXY: '', http_proxy: '', https_proxy: '', all_proxy: '',
      NO_PROXY: '127.0.0.1,localhost',
    },
  },
})
