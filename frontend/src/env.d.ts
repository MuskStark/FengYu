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
  __TAURI_INTERNALS__?: unknown
  __TAURI__?: unknown
}

/** Build-time app version, injected from package.json by vite.config.ts `define`. */
declare const __APP_VERSION__: string

/** Build timestamp (ISO-8601), captured at build / dev-server start by vite.config.ts `define`. */
declare const __APP_BUILD_TIME__: string
