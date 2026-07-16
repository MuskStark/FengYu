/// <reference types="vitest" />
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Standalone SPA build: bundles Vue + Vuetify into a self-contained dist/ the host
// serves inside the sandboxed iframe. Relative base so hashed asset URLs resolve
// under /plugin-runtime/{id}/.
export default defineConfig({
  plugins: [vue()],
  base: './',
  build: { outDir: 'dist', emptyOutDir: true },
  test: { environment: 'jsdom' },
})
