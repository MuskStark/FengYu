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
    name: 'zhiflow-vendor-vue',
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

export default defineConfig({
  define: {
    __APP_VERSION__: JSON.stringify(pkgVersion),
  },
  plugins: [
    vendorVue(),
    vue(),
    // MD3 Vuetify: auto tree-shake components, wire Sass overrides.
    vuetify({ styles: { configFile: 'src/plugins/vuetify-settings.scss' } }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: BACKEND, changeOrigin: true },
      '/plugin-ui': { target: BACKEND, changeOrigin: true },
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
