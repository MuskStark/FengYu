/**
 * Line-level helpers for the log viewer: level parsing that tolerates every format the
 * unified log directory actually contains —
 *   logback:      `2026-09-26 23:59:31.964 ERROR [main] fan.summer.X - boom`
 *   electron-log: `[2026-09-27 08:30:00.123] [error] [desktop] text`
 *   update.log:   `[2026-09-27T08:30:00.123Z] step text` (no level → null)
 *   nohup/raw:    arbitrary stdout
 */
export type LogLevel = 'error' | 'warn' | 'info' | 'debug'

// Two shapes are matched by SEPARATE regexes because a single pattern with the /i flag
// would also match lowercase prose ("the warning signs", "an error occurred"):
//   1. bare UPPERCASE tokens — logback's %-5level column;
//   2. bracketed lowercase tokens — electron-log's [level] column.
const LEVEL_PATTERNS: Array<[RegExp, LogLevel]> = [
  [/\bERROR\b/, 'error'],
  [/\[error\]/i, 'error'],
  [/\bWARN(?:ING)?\b/, 'warn'],
  [/\[\s*warn(?:ing)?\s*\]/i, 'warn'],
  [/\bINFO\b/, 'info'],
  [/\[info\]/i, 'info'],
  [/\bDEBUG\b|\bTRACE\b/, 'debug'],
  [/\[(?:debug|trace)\]/i, 'debug'],
]

/** The first level token on the line; null when the line carries none. */
export function parseLogLevel(line: string): LogLevel | null {
  for (const [pattern, level] of LEVEL_PATTERNS) {
    if (pattern.test(line)) return level
  }
  return null
}

/** Level filter applied by the viewer: null matches only unlevelled lines. */
export type LevelFilter = 'all' | 'errors' | 'warnings'

/** True when a line passes the active level filter (errors ⊂ warnings ⊂ all). */
export function matchesLevelFilter(line: string, filter: LevelFilter): boolean {
  if (filter === 'all') return true
  const level = parseLogLevel(line)
  if (filter === 'errors') return level === 'error'
  return level === 'error' || level === 'warn'
}

/** True when a line belongs to {@code pluginId} (logger column `plugin.<id>.<source>`). */
export function matchesPlugin(line: string, pluginId: string | null): boolean {
  if (pluginId == null || pluginId === '') return true
  return line.includes(` plugin.${pluginId}.`)
}

/** Split a tail payload into renderable lines (trailing newline dropped). */
export function toLogLines(content: string): string[] {
  const split = content.split('\n')
  if (split.length > 0 && split[split.length - 1] === '') split.pop()
  return split
}

/**
 * Wraps a line in ANSI SGR color codes matching its level — the log viewer renders ANSI
 * natively, so level coloring is expressed in the DATA (the component's design language)
 * instead of custom row renderers. Red=error, amber=warn, dim=debug/trace.
 */
export function withLevelAnsi(line: string): string {
  switch (parseLogLevel(line)) {
    case 'error': return `\u001B[31m${line}\u001B[0m`
    case 'warn': return `\u001B[38;5;172m${line}\u001B[0m`
    case 'debug': return `\u001B[90m${line}\u001B[0m`
    default: return line
  }
}
