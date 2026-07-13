import { defineConfig, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'
import vuetify from 'vite-plugin-vuetify'
import { fileURLToPath, URL } from 'node:url'
import { copyFileSync, mkdirSync, existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

// App version comes from package.json (single source of truth) and is injected
// as a build-time constant __APP_VERSION__ (see `define` below).
const pkgVersion = JSON.parse(
  readFileSync(resolve(__dirname, 'package.json'), 'utf8'),
).version as string

// The backend binds a fixed loopback port by default (HeadlessLauncher.DEFAULT_PORT = 24056),
// with a fallback to an OS-assigned port only if 24056 is taken — so the dev proxy targets the
// fixed port. If the backend fell back to a random port, restart it to free 24056.
const BACKEND = 'http://localhost:24056'

/**
 * Vue-sharing strategy (micro-frontend host — Task 11)
 * --------------------------------------------------------------------
 * Plugin UIs are separately-built ESM bundles that mark `vue` as
 * external and resolve the bare `vue` specifier at runtime via the
 * import map declared in index.html. To guarantee the shell and every
 * plugin share ONE Vue instance (so reactivity/instanceof work across
 * the boundary), the shell ALSO marks `vue` external and resolves it
 * through the same import map. The map points at a vendored ESM build
 * of Vue served from /vendor/vue.esm-browser.prod.js.
 *
 * This plugin copies that ESM build out of node_modules into public/
 * so it is served in dev and copied into dist/ on build.
 */
function vendorVue(): Plugin {
  return {
    name: 'fengyu-vendor-vue',
    apply: 'build', // dev is handled by serveVueInDev() middleware (avoids the publicDir guard)
    buildStart() {
      const src = resolve(__dirname, 'node_modules/vue/dist/vue.esm-browser.prod.js')
      const outDir = resolve(__dirname, 'public/vendor')
      const dest = resolve(outDir, 'vue.esm-browser.prod.js')
      if (existsSync(src)) {
        mkdirSync(outDir, { recursive: true })
        copyFileSync(src, dest)
      }
    },
  }
}

/**
 * Dev-only: share ONE Vue instance between the shell and plugin bundles.
 *
 * Micro-frontend plugins are loaded as raw browser ESM (served by the backend)
 * and resolve the bare `vue` specifier through index.html's import map →
 * `/vendor/vue.esm-browser.prod.js`. In a production build the shell also marks
 * `vue` external (see build.rollupOptions.external) so it resolves through that
 * same map — one Vue instance, so Vuetify (created by the shell, used by the
 * plugin) sees a valid component instance.
 *
 * Vite's dev server never mirrored that: the shell's `vue` was pre-bundled to
 * `/node_modules/.vite/deps/vue.js`, a SECOND Vue copy. A Vuetify component
 * mounted inside a plugin then hit `getCurrentInstance() === null` and crashed
 * with "Cannot read properties of null (reading 'refs')".
 *
 * The fix makes dev match prod with ONE served module: resolve every `vue`
 * import (shell source + its deps) to the vendored URL, and `load` that URL
 * from node_modules. The shell reaches it via resolveId; the plugin reaches
 * the SAME url via the browser import map — so Vite serves a single module
 * instance to both. Not `external` (the crawler would fail to prefetch it) and
 * not from public/ (whose import guard rejects source imports of assets).
 */
function shareVueInDev(): Plugin {
  const VENDOR_URL = '/vendor/vue.esm-browser.prod.js'
  const file = resolve(__dirname, 'node_modules/vue/dist/vue.esm-browser.prod.js')
  return {
    name: 'fengyu-share-vue-dev',
    enforce: 'pre',
    apply: 'serve',
    resolveId(id) {
      if (id === 'vue' || id === VENDOR_URL) return VENDOR_URL
      return null
    },
    load(id) {
      if (id === VENDOR_URL) return readFileSync(file, 'utf8')
      return null
    },
  }
}

export default defineConfig({
  define: {
    __APP_VERSION__: JSON.stringify(pkgVersion),
  },
  plugins: [
    vendorVue(),
    shareVueInDev(),
    vue(),
    // MD3 Vuetify: auto tree-shake components, wire Sass overrides.
    vuetify({ styles: { configFile: 'src/plugins/vuetify-settings.scss' } }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  optimizeDeps: {
    // Keep Vuetify OUT of esbuild's dependency pre-bundle. The whole library is pulled in
    // via `import * as components from 'vuetify/components'` (needed so micro-frontend
    // plugins can use any Vuetify component the host doesn't reference at compile time).
    // esbuild pre-bundling doesn't run vite-plugin-vuetify's Rollup style hooks, so the
    // per-component chunks embed raw .css with sourcemaps pointing at a non-existent
    // `.vite/deps/*.sass` (the `VApp.sass` 404), AND the `configFile` ($rounded) Sass
    // overrides are silently skipped. Excluding it serves Vuetify as source modules, so
    // every component's `.css` import is routed through the plugin's `.css`→virtual-Sass
    // transform.
    // Exclude `vue` too: shareVueInDev() externalizes it to the vendored URL so
    // the shell and plugins share one instance. If esbuild pre-bundled vue (or
    // inlined it into pinia/vue-router/vue-i18n), those deps would reference a
    // SECOND vue copy and break reactivity/instance lookups across the boundary.
    // Excluding it leaves bare `import 'vue'` for the resolver to rewrite.
    exclude: ['vuetify', 'vue'],
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: BACKEND, changeOrigin: true },
      '/plugin-ui': { target: BACKEND, changeOrigin: true },
      '/plugin-runtime': { target: BACKEND, changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    target: 'es2022',
    rollupOptions: {
      // vue is provided by the runtime import map (shared with plugins)
      external: ['vue'],
    },
  },
})
