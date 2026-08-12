/**
 * Unified CLI error presentation (T2-07). The CLI reports problems in a single
 * structured shape — {@code problem} (what went wrong), optional {@code file}
 * (where), optional {@code fix} (how to resolve it) — so a failure in `check`,
 * `dev`, `build`, or `init` reads the same regardless of which subsystem raised
 * it. Errors are rendered WITHOUT ANSI color escapes by design: the CLI is often
 * piped into logs or run in CI, and color noise there is harmful. Interactive
 * callers that want color can wrap the returned string themselves.
 */

const ANSI = /\u001b\[[0-9;]*[A-Za-z]/g

/** True when output is going to a live terminal and color has not been disabled. */
export function isInteractive(stream = process.stdout) {
  if (process.env.CI || process.env.NO_COLOR) return false
  return Boolean(stream?.isTTY)
}

/** Strip ANSI escape sequences from a string (defensive; the CLI emits none). */
export function stripAnsi(text) {
  return typeof text === 'string' ? text.replace(ANSI, '') : text
}

/**
 * Structured CLI error. Carries optional {@code file} and {@code fix} metadata
 * that {@link renderError} lays out as problem/file/fix. Throwing sites that
 * only have a message keep using plain {@code Error}; both render through the
 * same top-level handler so existing messages are not disturbed.
 */
export class FengYuCliError extends Error {
  constructor(problem, { file, fix } = {}) {
    super(problem)
    this.name = 'FengYuCliError'
    if (file) this.file = file
    if (fix) this.fix = fix
  }
}

/**
 * Render any thrown value as the unified CLI error block. Plain {@code Error}s
 * render as just their message; {@link FengYuCliError}s add the file/fix lines.
 * Never emits ANSI color. Used by {@code bin/fengyu.mjs} at the top level.
 */
export function renderError(err) {
  const problem = err && typeof err.message === 'string' ? err.message : String(err)
  const lines = [`fengyu: ${stripAnsi(problem).trim()}`]
  if (err?.file) lines.push(`  file: ${stripAnsi(err.file)}`)
  if (err?.fix) lines.push(`  fix:  ${stripAnsi(err.fix)}`)
  return lines.join('\n')
}
