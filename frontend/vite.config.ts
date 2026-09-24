import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { createHash } from 'node:crypto'
import { defineConfig, type Plugin } from 'vitest/config'

// The backend binds a fixed loopback port by default (HeadlessLauncher.DEFAULT_PORT = 24056).
// The dev proxy mirrors the retired Vue frontend's vite config: /api + /plugin-runtime → backend.
const BACKEND = 'http://localhost:24056'

/**
 * Build-only: bakes a Content-Security-Policy meta tag into the built index.html.
 *
 * Honored by BOTH release shapes: the WEB release is served over HTTP(S), and the DESKTOP
 * build loads through the shell's app:// custom protocol, where a meta CSP applies. The
 * desktop shell additionally derives its header policy's script-src hashes from this tag
 * (create-window.ts extractCspScriptHashes) — without it, production script-src fails open
 * to 'unsafe-inline'.
 *
 * The shell's theme-bootstrap (anti-flash) script is INLINE, so script-src needs its content
 * hash — 'self' alone would block it and white-screen the app. Everything else script-shaped
 * is an external same-origin file. (frame-ancestors is deliberately absent: it is ignored
 * inside a meta element.)
 *
 * connect-src/frame-src keep loopback wildcards: browser dev/access against a loopback
 * backend crosses ports (127.0.0.1:24056, localhost:5173), and the plugin iframes are
 * served from the loopback backend origin. wasm-unsafe-eval keeps WASM-based plugin
 * tooling (e.g. offline Python) functional without reopening eval.
 */
function webReleaseCsp(): Plugin {
  return {
    name: 'fengyu-web-release-csp',
    apply: 'build',
    transformIndexHtml(html) {
      let scriptSrc = "script-src 'self' 'wasm-unsafe-eval'"
      const inlineScripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)]
        .filter(([attrs]) => !/\bsrc\s*=/.test(attrs))
      for (const [, , body] of inlineScripts) {
        // Chromium applies CSP hash sources to the LF-normalized inline script content,
        // so hashing raw bytes breaks on a CRLF checkout (Windows core.autocrlf): the
        // script gets blocked and the app white-screens.
        const content = body.replace(/\r\n?/g, '\n')
        const hash = createHash('sha256').update(content).digest('base64')
        scriptSrc += ` 'sha256-${hash}'`
      }
      const policy = [
        "default-src 'self'",
        scriptSrc,
        "style-src 'self' 'unsafe-inline'",
        "img-src 'self' data: blob:",
        "font-src 'self' data:",
        "connect-src 'self' http://127.0.0.1:* http://localhost:* ws://127.0.0.1:* ws://localhost:*",
        "media-src 'self' blob:",
        "worker-src 'self' blob:",
        "frame-src http://127.0.0.1:* http://localhost:*",
        "object-src 'none'",
        "base-uri 'self'",
        "form-action 'self'",
      ].join('; ')
      return html.replace('<head>',
        `<head><meta http-equiv="Content-Security-Policy" content="${policy}">`)
    },
  }
}

export default defineConfig({
  base: './', // file:// packaging (desktop loadFile) — root paths break outside a server
  plugins: [react(), tailwindcss(), webReleaseCsp()],
  resolve: {
    alias: { '@': new URL('./src', import.meta.url).pathname },
  },
  server: {
    port: 5173, // matches the desktop shell's dev-frontend wait (dev-frontend.ts, --strictPort)
    proxy: {
      '/api': { target: BACKEND, changeOrigin: true },
      '/plugin-runtime': { target: BACKEND, changeOrigin: true },
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
