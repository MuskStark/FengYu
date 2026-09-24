import type { ReactNode } from 'react'

/**
 * One label→control settings row (the Vue shell's cx-setting-row pattern): a leading
 * lucide icon, a label with optional one-line hint, and the control on the right.
 */
export default function SettingRow({
  icon,
  label,
  hint,
  children,
}: {
  icon?: ReactNode
  label: string
  hint?: string
  children?: ReactNode
}) {
  return (
    <div className="cx-setting-row">
      <div className="cx-setting-row__label">
        {icon}
        <span className="set-row-text">
          {label}
          {hint ? <small>{hint}</small> : null}
        </span>
      </div>
      {children}
    </div>
  )
}
