// @infinia/plugin-ui — the Infinia React template kit for FengYu plugins.
//
// One design language with the host app (warm-white canvas, white panels,
// hairline borders, gold as the single loud interaction color, honeycomb
// marks). Aceternity UI components are intentionally NOT shipped in this
// package (their license forbids redistributing source files): fetch them
// per project with `fengyu add <name>`, which pulls straight from the
// official ui.aceternity.com registry under Aceternity's own license.

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

// Utilities
export { cn } from './lib/utils'
