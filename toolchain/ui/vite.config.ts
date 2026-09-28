import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))

// Library build: one ES bundle + one prebuilt Tailwind stylesheet
// (dist/plugin-ui.css). Consumers never run Tailwind — they import
// '@infinia/plugin-ui/style.css' and get every utility the kit emits.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': resolve(here, 'src') },
  },
  build: {
    lib: {
      entry: resolve(here, 'src/index.ts'),
      formats: ['es'],
      fileName: () => 'index.js',
    },
    rollupOptions: {
      // react/react-dom and the SDK stay external — the plugin project
      // provides them (exactly one React runtime per iframe).
      external: [
        'react',
        'react/jsx-runtime',
        'react-dom',
        'react-dom/client',
        '@infinia/plugin-sdk',
        /^@infinia\/plugin-sdk\//,
      ],
    },
    cssCodeSplit: false,
  },
})
