import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fengyuPluginDev } from '@infinia/plugin-dev'

// Standalone SPA build: bundles Vue + Vuetify into a self-contained dist/ the host
// serves inside the sandboxed iframe. Relative base so hashed asset URLs resolve
// under /plugin-runtime/{id}/.
//
// fengyuPluginDev turns the Vite dev server into a FengYu host simulator during `yarn dev`:
// it serves /__fengyu (the iframe shell running this UI with HMR) and forwards rpc.invoke to the
// loopback dev worker started by PluginDevMain (run it from your IDE — see src/test/java/...).

export default defineConfig({
  plugins: [
    vue(),
    fengyuPluginDev({
      manifest: '../manifest.json',
      workerEndpoint: { host: '127.0.0.1', port: 24057 },
    }),
  ],
  base: './',
  // @infinia/plugin-ui is a link: (symlink) into toolchain/ui, so its imports would otherwise
  // resolve vue/vuetify from the toolchain's own node_modules — dedupe keeps one instance.
  resolve: { dedupe: ['vue', 'vuetify'] },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  test: { environment: 'jsdom' },
})
