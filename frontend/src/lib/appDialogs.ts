/**
 * App-level modal dialogs — the in-app replacement for every native prompt surface
 * (`window.confirm`/`window.prompt` in the browser and the Electron preload's
 * message box). Framework-free pub/sub store: imperative callers get a Promise,
 * the single mounted host (shell/AppDialogHost) renders the dialog and reports
 * the user's answer back through `finishDialog`.
 */

export interface ConfirmDialogOptions {
  title?: string
  confirmLabel?: string
  cancelLabel?: string
  /** Destructive action — red confirm button, initial focus on cancel. */
  danger?: boolean
}

export interface PromptDialogOptions extends ConfirmDialogOptions {
  initial?: string
  placeholder?: string
}

/** What the host renders; the pending Promise's resolver never crosses this boundary. */
export interface DialogView {
  id: number
  kind: 'confirm' | 'prompt'
  message: string
  title?: string
  confirmLabel?: string
  cancelLabel?: string
  danger?: boolean
  initial?: string
  placeholder?: string
}

export function isPromptView(dialog: DialogView): dialog is DialogView & { kind: 'prompt' } {
  return dialog.kind === 'prompt'
}

interface PendingDialog extends DialogView {
  resolve: (value: boolean | string | null) => void
}

type DialogListener = (dialog: DialogView | null) => void

let seq = 0
let active: PendingDialog | null = null
const queue: PendingDialog[] = []
const listeners = new Set<DialogListener>()

function notify() {
  for (const listener of listeners) listener(active)
}

/** Activates the next queued dialog; true when the visible dialog changed. */
function pump(): boolean {
  if (active || !queue.length) return false
  active = queue.shift()!
  return true
}

/** Subscribe to the dialog the host should currently render (null = none). */
export function subscribeDialogs(listener: DialogListener): () => void {
  listeners.add(listener)
  listener(active)
  return () => { listeners.delete(listener) }
}

export function currentDialog(): DialogView | null {
  return active
}

/**
 * Resolve the active dialog. Unknown/stale ids are ignored so a late host
 * unmount can never resolve a newer dialog. Queued dialogs pump in FIFO order.
 */
export function finishDialog(id: number, value: boolean | string | null) {
  if (!active || active.id !== id) return
  const done = active
  active = null
  done.resolve(value)
  pump()
  notify()
}

function enqueue(view: Omit<PendingDialog, 'id' | 'resolve'>, resolve: PendingDialog['resolve']) {
  queue.push({ ...view, id: ++seq, resolve })
  if (pump()) notify()
}

export function appConfirm(message: string, options?: ConfirmDialogOptions): Promise<boolean> {
  return new Promise<boolean>((resolve) => {
    enqueue({ kind: 'confirm', message, ...options }, (value) => resolve(value === true))
  })
}

export function appPrompt(message: string, options?: PromptDialogOptions): Promise<string | null> {
  return new Promise<string | null>((resolve) => {
    enqueue({ kind: 'prompt', message, ...options }, (value) =>
      resolve(typeof value === 'string' ? value : null))
  })
}
