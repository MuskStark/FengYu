import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'
import { resolve, dirname } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))

/**
 * Dev-server config for the e2e workbench fixture.
 *
 * The library `vite.config.ts` is a lib-build config, so Playwright's
 * `webServer` runs Vite against this file instead: it serves `e2e/index.html`
 * with the Vue plugin enabled and the SDK alias resolved to the local source.
 */
export default defineConfig({
  root: resolve(__dirname, 'e2e'),
  plugins: [vue()],
  server: {
    port: 4175,
    strictPort: true,
    host: '127.0.0.1',
  },
})
