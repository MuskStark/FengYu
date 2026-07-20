import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fengyuPluginDev } from '@infinia/plugin-dev'

export default defineConfig({
  plugins: [
    vue(),
    // Turns the Vite dev server into a FengYu host simulator: serves /__fengyu (the iframe shell
    // that runs this plugin UI with HMR), and forwards rpc.invoke to the loopback dev worker.
    // Run PluginDevMain in your IDE (worker/src/test/java/...) to start the dev worker; set
    // breakpoints in the worker handlers — they fire directly, no JDWP remote attach.
    fengyuPluginDev({
      manifest: '../manifest.json',
      workerEndpoint: { host: '127.0.0.1', port: 24057 },
    }),
  ],
  base: './',
  build: { outDir: 'dist', emptyOutDir: true },
})
