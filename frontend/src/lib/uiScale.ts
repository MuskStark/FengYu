/**
 * The UI-scale knob: a 12–18 px root font basis persisted to localStorage and applied
 * through the document root's `zoom`. Shared by the appearance settings section (the
 * writer) and the startup path in main.tsx (the reader) — without the startup apply a
 * saved scale only came back after the user re-opened settings.
 */
const UI_SCALE_KEY = 'fengyu-ui-scale'
export const UI_SCALE_DEFAULT = 14

export function readUiScale(): number {
  const raw = window.localStorage.getItem(UI_SCALE_KEY)
  const parsed = raw == null ? NaN : Number(raw)
  return Number.isFinite(parsed) && parsed >= 12 && parsed <= 18 ? parsed : UI_SCALE_DEFAULT
}

export function applyUiScale(value: number): void {
  // `zoom` scales the WHOLE interface uniformly (px text, rem spacing, canvases) — the
  // desktop webview is Chromium, and modern Firefox/Safari support it too.
  document.documentElement.style.zoom = String(value / UI_SCALE_DEFAULT)
}

export function persistUiScale(value: number): void {
  window.localStorage.setItem(UI_SCALE_KEY, String(value))
}
