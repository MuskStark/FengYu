import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'node:path'

// Single config drives both the Vite library build and Vitest (jsdom),
// matching the OfficialPlugins/plugin-email/ui-src pattern.
export default defineConfig({
  plugins: [vue()],
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
