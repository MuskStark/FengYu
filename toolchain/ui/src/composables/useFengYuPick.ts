import { ref } from 'vue'
import type { FileRef } from '@infinia/plugin-sdk'

/**
 * Event emit signature shared by the SDK-backed pickers. Mirrors the
 * `defineEmits` of {@link FyFilePicker} / {@link FyDirectoryPicker}:
 * `update:modelValue` (the `FileRef`, or `null`), `cancel`, and `error`.
 */
export interface FengYuPickEmit {
  (event: 'update:modelValue', value: FileRef | null): void
  (event: 'cancel'): void
  (event: 'error', error: Error): void
}

/**
 * Options for {@link useFengYuPick}. The composable owns the pick lifecycle
 * (loading, error/permission routing, emit contract) and delegates the actual
 * SDK invocation to `request`, so each picker parameterizes only the call.
 */
export interface UseFengYuPickOptions {
  /** Invokes the SDK call and resolves the `FileRef`, or `null` when cancelled. */
  request: () => Promise<FileRef | null>
  /** Emit function for v-model + lifecycle events. */
  emit: FengYuPickEmit
}

/**
 * A rejection whose message reads as a permission/access denial. Centralized
 * here so {@link FyFilePicker} and {@link FyDirectoryPicker} share one heuristic.
 */
export function isPermissionError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error ?? '')
  return /permission|denied|forbidden|unauthorized|not allowed/i.test(message)
}

/**
 * Shared pick lifecycle for the SDK-backed pickers. Owns `loading`,
 * `errorMessage`, and `permissionDenied`, and implements the behavioral
 * contract both pickers share:
 *
 * - Concurrent clicks are guarded by `loading`.
 * - The previous error/permission state is cleared before a new request.
 * - A `null` result is a normal cancellation: emit `update:modelValue(null)` +
 *   `cancel`, render NO alert.
 * - A rejection is wrapped as an `Error`, routed to `permissionDenied` (via
 *   {@link isPermissionError}) or `errorMessage`, and emitted via `error`.
 *
 * The component keeps the rendering decision (which notice to show) in its own
 * template; this composable only surfaces the state.
 */
export function useFengYuPick(options: UseFengYuPickOptions) {
  const { request, emit } = options
  const loading = ref(false)
  const errorMessage = ref<string | null>(null)
  const permissionDenied = ref(false)

  async function pick(): Promise<void> {
    // Guard concurrent clicks with `loading`.
    if (loading.value) return
    // Clear the previous error before a new request.
    errorMessage.value = null
    permissionDenied.value = false
    loading.value = true
    try {
      const result = await request()
      if (result) {
        emit('update:modelValue', result)
      } else {
        // Cancellation: a normal empty result — emit null + cancel, no alert.
        emit('update:modelValue', null)
        emit('cancel')
      }
    } catch (error) {
      const wrapped = error instanceof Error ? error : new Error(String(error))
      emit('error', wrapped)
      errorMessage.value = wrapped.message
      permissionDenied.value = isPermissionError(wrapped)
    } finally {
      loading.value = false
    }
  }

  return { loading, errorMessage, permissionDenied, pick }
}
