import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import cssInjectedByJs from 'vite-plugin-css-injected-by-js';
import { fileURLToPath } from 'node:url';

// Library/ESM build for the FengYu micro-frontend contract.
// - `vue` is external: the host shell provides it via an import map.
// - Single ESM entry (src/main.ts) → single `index.js` (no hash).
// - CSS is inlined into the JS (cssCodeSplit:false) + styles are injected from
//   within the component, so a single index.js is fully self-contained.
export default defineConfig({
  // css-injected-by-js folds the emitted CSS into the JS bundle and injects a
  // <style> at runtime, so a single self-contained index.js ships (no index.css).
  plugins: [vue(), cssInjectedByJs()],
  build: {
    outDir: fileURLToPath(new URL('../src/main/resources/ui/markdown/', import.meta.url)),
    emptyOutDir: true,
    cssCodeSplit: false,
    // Keep output readable-ish and small; minify for production.
    minify: 'esbuild',
    lib: {
      entry: fileURLToPath(new URL('./src/main.ts', import.meta.url)),
      formats: ['es'],
      fileName: () => 'index.js'
    },
    rollupOptions: {
      external: ['vue'],
      output: {
        // Import-map-compatible: leave the bare specifier `vue` as-is so the
        // host import map resolves it.
        paths: { vue: 'vue' },
        // Avoid separate chunk files; keep a single index.js.
        inlineDynamicImports: true
      }
    }
  }
});
