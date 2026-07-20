import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fengyuPluginDev } from '@infinia/plugin-dev'

export default defineConfig({
  plugins: [
    vue(),
    // Turns the Vite dev server into a FengYu host simulator. UI-only plugins (no backend worker)
    // run in mock mode: rpc.invoke returns a deterministic stub, so you can iterate the UI before
    // any worker exists. Point workerEndpoint at a dev worker if you add one later.
    fengyuPluginDev({ manifest: './manifest.json', mockWorker: true }),
  ],
  base: './',
  build: { outDir: 'ui', emptyOutDir: true },
})
