import { defineConfig } from '@playwright/test'
import { fileURLToPath } from 'node:url'

// Exercise the real host and built plugin UIs; API responses are isolated fixtures.
export default defineConfig({
  testDir: './e2e',
  testMatch: 'host-rc.spec.ts',
  workers: 1,
  forbidOnly: !!process.env.CI,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:4176',
    viewport: { width: 960, height: 640 },
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'yarn run dev --host 127.0.0.1 --port 4176 --strictPort',
    cwd: fileURLToPath(new URL('../../frontend', import.meta.url)),
    url: 'http://127.0.0.1:4176',
    timeout: 60_000,
  },
})
