import { defineConfig } from '@playwright/test'
import { fileURLToPath } from 'node:url'
import { resolve, dirname } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))

/**
 * Playwright config for the workbench visual-regression + axe suite.
 *
 * `webServer` boots a Vite dev server that serves `e2e/index.html` (see
 * `vite.e2e.config.ts`). Each visual case owns its exact viewport, theme, and
 * wizard state; no emulated device contributes implicit dimensions.
 */
export default defineConfig({
  testDir: resolve(__dirname, 'e2e'),
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:5173',
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'chromium',
      use: { browserName: 'chromium' },
    },
  ],
  webServer: {
    command: 'vite --config vite.e2e.config.ts',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
    cwd: resolve(__dirname),
  },
})
