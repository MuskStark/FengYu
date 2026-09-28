// @infinia/plugin-ui 3.0 — the Infinia React template kit for FengYu plugins.
//
// One design language with the host app (warm-white canvas, white panels,
// hairline borders, gold as the single loud interaction color, honeycomb
// marks) on official Aceternity components. Interactive Aceternity pieces are
// re-exported so plugins never add those dependencies themselves.

import './styles/plugin-ui.css'

// Bootstrap / DI
export { mountFengYuApp, type MountFengYuAppOptions } from './app'
export { FengYuClientProvider, FengYuClientContext, useFengYuClient } from './client'
export {
  bindFengYuEnvironment,
  themeClass,
  localeName,
  type FengYuEnvironmentBindingOptions,
} from './environment'

// i18n
export {
  createFengYuI18n,
  normalizeFengYuLocale,
  FengYuI18nProvider,
  FengYuI18nContext,
  useFengYuI18n,
  type FengYuI18n,
  type FengYuMessages,
  type FengYuMessageTables,
} from './i18n'

// Notifications
export {
  useFengYuNotify,
  sendFengYuNotification,
  NotifyProvider,
  NotifyHost,
  type FyNotification,
  type FyNotificationOptions,
  type FyNotificationTone,
  type FengYuNotifyApi,
} from './notify'

// Wizard state machine (framework-neutral; snapshot format identical to the
// Vue 2.x kit so persisted snapshots survive the React migration).
export * from './wizard'
export {
  StepWizard,
  type StepWizardStep,
  type StepWizardRenderProps,
  type StepWizardLabels,
} from './components/wizard'

// Template chrome & primitives
export {
  PluginShell,
  PluginBar,
  PluginHeader,
  StatusBar,
  HexMark,
  Chip,
  StatusChip,
  GoldButton,
  GhostButton,
  type PluginBarTab,
  type StatusTone,
} from './components/chrome'
export { Page, PageHeader } from './components/page'
export {
  EmptyState,
  LoadingState,
  ErrorState,
  PermissionNotice,
  Progress,
  type FyProgressStatus,
} from './components/states'
export { FilePicker, DirectoryPicker, isPermissionError } from './components/pickers'
export {
  Select,
  Combobox,
  type SelectOption,
  type SelectSize,
} from './components/select'
export { ConfirmDialog } from './components/confirm'

// SDK type re-exports (stable imports for plugin code)
export type { FengYuClient, Environment, Theme, FileRef, FileFilter } from '@infinia/plugin-sdk'

// ── Official Aceternity components (ui.aceternity.com, vendored) ──────────
export { Sidebar, SidebarBody, SidebarProvider, SidebarLink, useSidebar } from './components/aceternity/sidebar'
export { PlaceholdersAndVanishInput } from './components/aceternity/placeholders-and-vanish-input'
export { CardSpotlight } from './components/aceternity/card-spotlight'
export { TextGenerateEffect } from './components/aceternity/text-generate-effect'
export { FloatingDock } from './components/aceternity/floating-dock'
export { FileUpload } from './components/aceternity/file-upload'
export { MultiStepLoader } from './components/aceternity/multi-step-loader'
export { Meteors } from './components/aceternity/meteors'
export { GlowingEffect } from './components/aceternity/glowing-effect'
export { Tabs as AceternityTabs } from './components/aceternity/tabs'
export { CardStack } from './components/aceternity/card-stack'
export { Button as StatefulButton } from './components/aceternity/stateful-button'
export { Button as MovingBorderButton } from './components/aceternity/moving-border'
export { Terminal } from './components/aceternity/terminal'
export { cn } from './lib/utils'
