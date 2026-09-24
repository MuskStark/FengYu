/**
 * Hairline toggle switch (cx-kit port of the Vue sources/skills panels'
 * .cx-switch): a hidden native checkbox drives the track/thumb, so keyboard
 * and screen-reader behavior come for free.
 */
export function ToggleSwitch({ checked, disabled, label, title, onChange }: {
  checked: boolean
  disabled?: boolean
  /** Visible text next to the track (the action the flip performs, e.g. "Disable"). */
  label?: string
  title?: string
  onChange: (next: boolean) => void
}) {
  return (
    <label className="pg-switch" title={title}>
      <input
        type="checkbox"
        checked={checked}
        disabled={disabled}
        aria-label={title ?? label}
        onChange={event => onChange(event.target.checked)}
      />
      <span className="pg-switch__track" />
      <span className="pg-switch__thumb" aria-hidden="true" />
      {label && <span className="cx-muted">{label}</span>}
    </label>
  )
}
