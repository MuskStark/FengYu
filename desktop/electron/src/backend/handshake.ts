/**
 * Parse a `FENGYU_PORT=<n>` line from the backend's stdout.
 * Returns the port, or null if the line isn't a port announcement.
 * Mirrors Rust `line.strip_prefix("FENGYU_PORT=")` + `parse::<u16>()`.
 */
export function parseFengyuPort(line: string): number | null {
  const trimmed = line.trim()
  const prefix = 'FENGYU_PORT='
  if (!trimmed.startsWith(prefix)) return null
  const rest = trimmed.slice(prefix.length).trim()
  const n = Number(rest)
  if (!Number.isInteger(n) || n < 0 || n > 65535) return null
  return n
}

/**
 * Crude SETUP-mode detection from the /api/setup/status body.
 * Mirrors Rust `check_setup_mode`: SETUP if `"initialized":false` (compact or spaced).
 */
export function detectSetupMode(body: string): boolean {
  return body.includes('"initialized":false') || body.includes('"initialized": false')
}
