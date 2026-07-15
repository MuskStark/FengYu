/**
 * @fengyu/plugin-ui — Codex-style Vuetify foundation for FengYu generated plugins.
 *
 * Public surface:
 * - {@link createFengYuVuetify}: build a pre-configured Vuetify instance.
 * - {@link bindFengYuEnvironment}: sync theme/locale from the host SDK.
 * - {@link provideFengYuClient} / {@link useFengYuClient}: per-app client DI.
 * - {@link themeName} / {@link localeName}: environment → Vuetify id helpers.
 * - {@link fengyuCodexLight} / {@link fengyuCodexDark}: theme definitions.
 * - {@link fengyuDefaults}: global Vuetify defaults.
 */

export { createFengYuVuetify, bindFengYuEnvironment, themeName, localeName } from './createFengYuVuetify'
export type { FengYuVuetifyOptions } from './createFengYuVuetify'
export { fengyuCodexLight, fengyuCodexDark } from './theme'
export { fengyuDefaults } from './defaults'
export { provideFengYuClient, useFengYuClient, FENGYU_CLIENT_KEY } from './client'

// Re-export the SDK types this library consumes, so plugin authors have a
// single import surface for the host bindings.
export type { FengYuClient, Environment, Theme, FileRef } from '@fengyu/plugin-sdk'
