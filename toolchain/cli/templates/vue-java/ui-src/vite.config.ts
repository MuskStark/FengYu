import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fengyuPluginDev } from '@infinia/plugin-dev'

export default defineConfig({
  plugins: [
    vue(),
    // Turns the Vite dev server into a FengYu host simulator: serves /__fengyu (the iframe shell
    // that runs this plugin UI with HMR), and forwards rpc.invoke to the loopback dev worker.
    // Start the worker with `{{devWorkerCommand}}`; breakpoints in handlers fire directly.
    fengyuPluginDev({
      manifest: '../target/fengyu-manifest/manifest.json',
      workerEndpoint: { host: '127.0.0.1', port: 24057 },
    }),
  ],
  base: './',
  build: { outDir: 'dist', emptyOutDir: true },
})
