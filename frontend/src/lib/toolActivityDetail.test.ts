import { describe, expect, test } from 'vitest'
import { applyToolActivity, type ToolActivity } from './toolActivity'

/** Drives applyToolActivity through one full call/result pair. */
function runTool(name: string, args: Record<string, unknown>, output: string): ToolActivity[] {
  const items: ToolActivity[] = []
  applyToolActivity(items, {
    phase: 'call', id: 'c1', name, arguments: args,
  })
  applyToolActivity(items, {
    phase: 'result', id: 'c1', name, success: true, output,
  })
  return items
}

/** The ZCode-style inline key-argument summary: what the collapsed row shows. */
describe('activityDetail (inline key argument)', () => {
  test('file tools summarize the path', () => {
    expect(runTool('read_file', { path: 'src/main.ts' }, '{}')[0]?.detail).toBe('src/main.ts')
  })

  test('exec summarizes the command (cwd only as a fallback)', () => {
    expect(runTool('workspace_exec', { command: 'mvn test -q' }, '{}')[0]?.detail)
      .toBe('mvn test -q')
    expect(runTool('execute_command', { workingDirectory: '/ws' }, '{}')[0]?.detail).toBe('/ws')
  })

  test('grep joins pattern and path; glob shows the pattern', () => {
    expect(runTool('grep', { pattern: 'setConversations', path: 'src/' }, '{}')[0]?.detail)
      .toBe('setConversations src/')
    expect(runTool('glob', { pattern: '**/*.tsx' }, '{}')[0]?.detail).toBe('**/*.tsx')
  })

  test('unknown tools fall back to cwd/workingDirectory, else empty', () => {
    expect(runTool('some_tool', { cwd: '/tmp' }, '')[0]?.detail).toBe('/tmp')
    expect(runTool('some_tool', {}, '')[0]?.detail).toBe('')
  })
})
