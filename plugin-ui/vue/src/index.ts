/**
 * @infinia/plugin-ui — Codex-style Vuetify foundation for FengYu generated plugins.
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

// Layout and standard state components.
export { default as FyPluginShell } from './components/FyPluginShell.vue'
export type { FyNavItem } from './components/FyPluginShell.vue'
export { default as FyIcon } from './components/FyIcon.vue'
export { default as FyPageHeader } from './components/FyPageHeader.vue'
export { default as FyToolbar } from './components/FyToolbar.vue'
export { default as FyEmptyState } from './components/FyEmptyState.vue'
export { default as FyLoadingState } from './components/FyLoadingState.vue'
export { default as FyErrorState } from './components/FyErrorState.vue'
export { default as FyPermissionNotice } from './components/FyPermissionNotice.vue'

// SDK-integrated components.
export { default as FyFilePicker } from './components/FyFilePicker.vue'
export { default as FyDirectoryPicker } from './components/FyDirectoryPicker.vue'
export { default as FyNotificationCenter } from './components/FyNotificationCenter.vue'

// Workflow components.
export { default as FyStepWizard } from './components/FyStepWizard.vue'
export {
  FY_WIZARD_SNAPSHOT_VERSION,
  FY_WIZARD_DEFAULT_LABELS,
  buildWizardSnapshot,
  createWizardStates,
  invalidateWizardStates,
  normalizeWizardSnapshot,
} from './wizard'
export type {
  FyWizardSnapshot,
  FyWizardActionsSlotProps,
  FyWizardErrorSlotProps,
  FyWizardLabels,
  FyWizardLabelsInput,
  FyWizardSnapshotResult,
  FyWizardSlotActions,
  FyWizardStep,
  FyWizardStepLabelSlotProps,
  FyWizardStepSlotProps,
  FyWizardStepState,
  FyWizardStepStatus,
  FyWizardStatusLabels,
  FyWizardValidationResult,
} from './wizard'
export { default as FyConfirmDialog } from './components/FyConfirmDialog.vue'
export { default as FyTaskTable } from './components/FyTaskTable.vue'
export type { FyTaskRow, FyTaskStatus } from './components/FyTaskTable.vue'

// Notification composable + the host-fallback helper it builds on.
export { useFengYuNotify, sendFengYuNotification } from './composables/useFengYuNotify'

// Re-export the SDK types this library consumes, so plugin authors have a
// single import surface for the host bindings.
export type { FengYuClient, Environment, Theme, FileRef, FileFilter } from '@infinia/plugin-sdk'
