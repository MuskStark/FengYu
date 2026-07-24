import { defineConfig } from 'vite'
import type { Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fengyuPluginDev } from '@infinia/plugin-dev'

// Standalone SPA build: bundles Vue + Vuetify into a self-contained dist/ the host
// serves inside the sandboxed iframe. Relative base so hashed asset URLs resolve
// under /plugin-runtime/{id}/.
//
// fengyuPluginDev turns the Vite dev server into a FengYu host simulator during `npm run dev`:
// it serves /__fengyu (the iframe shell running this UI with HMR) and forwards rpc.invoke to the
// loopback dev worker started by PluginDevMain (run it from your IDE — see src/test/java/...).

/**
 * Inject the MDI webfont as real asset files, overriding the base64 `data:`
 * version that @infinia/plugin-ui inlines into its style.css.
 *
 * The host serves plugin UIs under an iframe CSP with no `font-src`, so fonts
 * fall back to `default-src 'self'`, which blocks `data:` URIs — every MDI
 * glyph renders as a tofu box. Re-importing the font CSS here makes Vite emit
 * the >4KB font binaries as real hashed files (see `assetsInlineLimit: 0`),
 * which load fine under `'self'`. The import is injected right AFTER
 * @infinia/plugin-ui's style.css import so this same-named `@font-face` comes
 * later in the CSS chunk and wins the cascade. Kept out of `src/` (done via
 * transform, not a literal in source) so it does not trip the "no @mdi/font in
 * plugin source" compliance check.
 */
function mdiFontFiles(): Plugin {
  return {
    name: 'fengyu-mdi-font-files',
    transform(code, id) {
      if (!id.endsWith('/src/main.ts')) return
      // Insert immediately after plugin-ui's style.css import line so the
      // real-file @font-face is emitted AFTER (and thus overrides) the
      // inlined `data:` one. Vite normalizes import quotes to double quotes
      // before transform, so match either quote style.
      const m = code.match(/import\s+["']@infinia\/plugin-ui\/style\.css["']/)
      if (!m) return
      const end = m.index! + m[0].length
      return `${code.slice(0, end)}\nimport "@mdi/font/css/materialdesignicons.css"${code.slice(end)}`
    },
  }
}

export default defineConfig({
  plugins: [
    vue(),
    mdiFontFiles(),
    fengyuPluginDev({
      manifest: '../manifest.json',
      workerEndpoint: { host: '127.0.0.1', port: 24057 },
    }),
  ],
  base: './',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    // MDI font must ship as real files: the host iframe CSP has no `font-src`,
    // so it falls back to `default-src 'self'`, which blocks the base64 `data:`
    // font URIs that @infinia/plugin-ui inlines. Keeping these >4KB font files
    // as emitted assets (instead of inline data:) lets them load under 'self'.
    assetsInlineLimit: 0,
  },
  test: { environment: 'jsdom' },
})
