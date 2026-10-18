import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { fengyuDocsContent } from './scripts/vite-plugin-content.mjs'

export default defineConfig({
  // GitHub Pages project site (muskstark.github.io/FengYu/) — every emitted
  // URL carries this prefix; emit-static.mjs stamps one HTML shell per route.
  base: '/FengYu/',
  plugins: [react(), tailwindcss(), fengyuDocsContent()],
  resolve: {
    alias: { '@': new URL('./src', import.meta.url).pathname },
  },
})
