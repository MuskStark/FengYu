import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath } from 'node:url'
import { resolve, dirname } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))

/**
 * Dev-server config for the e2e workbench fixture.
 *
 * The library `vite.config.ts` is a lib-build config, so Playwright's
 * `webServer` runs Vite against this file instead: it serves `e2e/index.html`
 * with React and the kit's source directly.
 */
export default defineConfig({
  root: resolve(here, 'e2e'),
  plugins: [react()],
  resolve: {
    alias: { '@': resolve(here, 'src') },
  },
  server: {
    port: 4175,
    strictPort: true,
    host: '127.0.0.1',
  },
})
