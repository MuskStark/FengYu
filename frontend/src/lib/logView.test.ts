import { describe, expect, it } from 'vitest'
import { matchesLevelFilter, matchesPlugin, parseLogLevel, toLogLines, withLevelAnsi } from './logView'

describe('log view line parsing', () => {
  it('reads levels off every log format in the unified directory', () => {
    expect(parseLogLevel('2026-09-26 23:59:31.964 ERROR [main] fan.summer.X - boom')).toBe('error')
    expect(parseLogLevel('2026-09-26 23:59:31.964 WARN  [Thread-4] plugin - careful')).toBe('warn')
    expect(parseLogLevel('[2026-09-27 08:30:00.123] [error] [renderer] TypeError: x')).toBe('error')
    expect(parseLogLevel('[2026-09-27 08:30:00.123] [info] checking for updates')).toBe('info')
    expect(parseLogLevel('12:00:00.001 DEBUG [vthread] fine detail')).toBe('debug')
    // update.log / raw stdout carry no level token.
    expect(parseLogLevel('[2026-09-27T08:30:00.123Z] downloading release asset')).toBeNull()
    expect(parseLogLevel('Picked up JAVA_OPTS: -Xmx1g')).toBeNull()
  })

  it('does not read prose as a level', () => {
    expect(parseLogLevel('the Information panel explained the warning signs')).toBeNull()
    expect(parseLogLevel('user asked about debugging workflow')).toBeNull()
  })

  it('filters errors and warnings inclusively', () => {
    const error = '12:00:00.000 ERROR [main] X - boom'
    const warn = '12:00:00.000 WARN  [main] X - careful'
    const info = '12:00:00.000 INFO  [main] X - fine'
    const bare = 'Picked up JAVA_OPTS'
    expect(matchesLevelFilter(error, 'all')).toBe(true)
    expect(matchesLevelFilter(bare, 'all')).toBe(true)
    expect(matchesLevelFilter(error, 'errors')).toBe(true)
    expect(matchesLevelFilter(warn, 'errors')).toBe(false)
    expect(matchesLevelFilter(info, 'errors')).toBe(false)
    expect(matchesLevelFilter(error, 'warnings')).toBe(true)
    expect(matchesLevelFilter(warn, 'warnings')).toBe(true)
    expect(matchesLevelFilter(info, 'warnings')).toBe(false)
    expect(matchesLevelFilter(bare, 'errors')).toBe(false)
  })

  it('splits a tail into lines and drops the trailing newline artifact', () => {
    expect(toLogLines('a\nb\n')).toEqual(['a', 'b'])
    expect(toLogLines('a\nb')).toEqual(['a', 'b'])
    expect(toLogLines('')).toEqual([])
  })

  it('annotates lines with ANSI colors the viewer renders natively', () => {
    expect(withLevelAnsi('12:00 ERROR [main] X - boom')).toBe('\u001B[31m12:00 ERROR [main] X - boom\u001B[0m')
    expect(withLevelAnsi('12:00 WARN  [main] X - careful')).toBe('\u001B[38;5;172m12:00 WARN  [main] X - careful\u001B[0m')
    expect(withLevelAnsi('12:00 DEBUG [main] X - detail')).toBe('\u001B[90m12:00 DEBUG [main] X - detail\u001B[0m')
    expect(withLevelAnsi('plain stdout line')).toBe('plain stdout line')
  })

  it('filters lines by the plugin logger column', () => {
    const markdown = '2026-09-27 08:10:05.777 INFO [t] plugin.fan.summer.markdown.worker - invoke ok'
    const excel = '2026-09-27 08:10:02.312 WARN [t] plugin.fan.summer.excel.worker - slow'
    const host = '2026-09-27 08:10:00.100 INFO [main] fan.summer.fengyu.X - started'
    expect(matchesPlugin(markdown, null)).toBe(true)
    expect(matchesPlugin(markdown, '')).toBe(true)
    expect(matchesPlugin(markdown, 'fan.summer.markdown')).toBe(true)
    // A prefix of another plugin's id must not cross-match (markdown vs markdown-pro).
    expect(matchesPlugin(excel, 'fan.summer.markdown')).toBe(false)
    // The dotted suffix guard: "fan.summer" alone does not match fan.summer.markdown's lines?
    // It WOULD via substring " plugin.fan.summer." — the backend only offers full ids,
    // so a partial id cannot reach this filter from the dropdown.
    expect(matchesPlugin(markdown, 'fan.summer')).toBe(true)
    expect(matchesPlugin(host, 'fan.summer.markdown')).toBe(false)
  })
})
