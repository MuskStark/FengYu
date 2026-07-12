/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}

interface ImportMetaEnv {
  readonly VITE_FENGYU_TOKEN?: string
  readonly VITE_FENGYU_API_BASE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

interface Window {
  __FENGYU_TOKEN__?: string
  __FENGYU_API_BASE__?: string
}

/** Build-time app version, injected from package.json by vite.config.ts `define`. */
declare const __APP_VERSION__: string
