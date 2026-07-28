import { defineConfig } from 'vite'
import type { Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'
import { readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const mdiFontCss = '@import "@mdi/font/css/materialdesignicons.css";'

/**
 * Keep the MDI stylesheet as a CSS-level dependency. Importing it from
 * createFengYuVuetify would either inline every font in Vite library mode or
 * leave a CSS import in JavaScript that Node-based test/SSR loaders cannot
 * execute. Consumer app builds resolve this @import and emit hashed fonts.
 */
function externalMdiFontCss(): Plugin {
  return {
    name: 'fengyu-external-mdi-font-css',
    async closeBundle() {
      const stylesheet = resolve(__dirname, 'dist/style.css')
      let css: string
      try {
        css = await readFile(stylesheet, 'utf8')
      } catch {
        throw new Error('plugin-ui build did not emit style.css')
      }
      await writeFile(stylesheet, `${mdiFontCss}\n${css}`)
    },
  }
}

// Single config drives both the Vite library build and Vitest (jsdom),
// matching the OfficialPlugins/plugin-email/ui-src pattern.
export default defineConfig({
  plugins: [vue(), externalMdiFontCss()],
  build: {
    lib: {
      entry: resolve(__dirname, 'src/index.ts'),
      name: 'FengYuPluginUI',
      fileName: 'index',
      formats: ['es'],
    },
    cssCodeSplit: false,
    sourcemap: true,
    emptyOutDir: true,
    rollupOptions: {
      // Peer deps are externalized; consumers bring their own Vue/Vuetify/SDK.
      external: ['vue', 'vuetify', '@infinia/plugin-sdk'],
      output: {
        // Emit the single bundled stylesheet as dist/style.css so the
        // "./style.css" subpath export resolves correctly.
        assetFileNames: 'style.[ext]',
      },
    },
  },
  test: {
    environment: 'jsdom',
    // Keep Vitest out of the Playwright directory: `e2e/workbench.spec.ts`
    // matches Vitest's default `*.spec.ts` glob, but it is a Playwright visual
    // suite (`test.describe` from @playwright/test) that must NOT run under
    // Vitest. Same for the Playwright config itself.
    exclude: [
      '**/node_modules/**',
      '**/dist/**',
      'test/build-output.test.mjs',
      'e2e/**',
      'playwright.config.ts',
    ],
    // jsdom has no ResizeObserver; Vuetify's layout system (`v-app`, `v-main`,
    // `v-navigation-drawer`, `useDisplay`) installs one via
    // `useResizeObserver`, so mounting those components under jsdom needs a
    // stub. See test/setup.ts.
    setupFiles: ['test/setup.ts'],
    // Vuetify's prebuilt component CSS is `.css` imported from inside the
    // package; under jsdom Vitest tries to load these as modules and throws
    // "Unknown file extension .css". Stub all CSS to an empty module so the
    // component graph loads without a real style pipeline.
    css: false,
    server: {
      deps: {
        // Inline vuetify so its internal `.css` imports are resolved by Vite's
        // CSS plugin instead of Node's loader.
        inline: ['vuetify'],
      },
    },
  },
})
