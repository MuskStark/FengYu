import { describe, expect, it } from 'vitest'
import { applyToolActivity, activityLabel, diffLines, type ToolActivity } from './toolActivity'

describe('AI tool activity timeline', () => {
  it('renders skill loading like Codex and completes the same row', () => {
    const items: ToolActivity[] = []
    applyToolActivity(items, { phase: 'call', id: 'c1', name: 'skill', arguments: { id: 'fengyu-plugin-dev' } })
    expect(items[0]).toMatchObject({ label: 'Read FengYu Plugin Dev skill', status: 'running' })
    applyToolActivity(items, { phase: 'result', id: 'c1', success: true })
    expect(items[0].status).toBe('completed')
  })

  it('shows command approvals as a waiting activity', () => {
    const items: ToolActivity[] = []
    applyToolActivity(items, { phase: 'approval_required', id: 'c2', approvalId: 'a1',
      name: 'execute_command', arguments: { command: './mvnw test' } })
    expect(items[0]).toMatchObject({ label: 'Run ./mvnw test', status: 'waiting' })
  })
})

describe('workspace coding tool activities', () => {
  it('labels the five coding tools by their file or pattern argument', () => {
    expect(activityLabel('read_file', { path: 'src/A.java' })).toBe('Read src/A.java')
    expect(activityLabel('write_file', { path: 'src/A.java' })).toBe('Write src/A.java')
    expect(activityLabel('edit_file', { path: 'src/A.java' })).toBe('Edit src/A.java')
    expect(activityLabel('grep', { pattern: 'TODO' })).toBe('Search TODO')
    expect(activityLabel('glob', { pattern: '*.ts' })).toBe('Find *.ts')
  })

  it('extracts the unified diff from an edit_file result payload', () => {
    const items: ToolActivity[] = []
    applyToolActivity(items, { phase: 'call', id: 'e1', name: 'edit_file',
      arguments: { path: 'a.txt', old_string: 'x', new_string: 'y' } })
    applyToolActivity(items, { phase: 'result', id: 'e1', success: true,
      output: '{"success":true,"path":"a.txt","diff":"--- a/a.txt\\n+++ b/a.txt\\n@@ -1 +1 @@\\n-x\\n+y\\n"}' })
    expect(items[0].diff).toBe('--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-x\n+y\n')
  })

  it('records the workspace-relative path of file tools for the open-file gesture', () => {
    const items: ToolActivity[] = []
    applyToolActivity(items, { phase: 'call', id: 'w1', name: 'write_file', arguments: { path: 'src/main.rs' } })
    applyToolActivity(items, { phase: 'call', id: 'g1', name: 'grep', arguments: { pattern: 'x' } })
    expect(items[0].path).toBe('src/main.rs')
    expect(items[1].path).toBeUndefined()
  })

  it('never treats other tools or malformed output as diffs', () => {
    const items: ToolActivity[] = []
    applyToolActivity(items, { phase: 'call', id: 'r1', name: 'read_file', arguments: { path: 'a' } })
    applyToolActivity(items, { phase: 'result', id: 'r1', success: true,
      output: '{"success":true,"content":"not a diff"}' })
    expect(items[0].diff).toBeUndefined()
  })
})

describe('diffLines rendering rows', () => {
  it('classifies header, hunk, added, removed, and context lines', () => {
    const rows = diffLines('--- a/f.txt\n+++ b/f.txt\n@@ -1,2 +1,2 @@\n keep\n-old\n+new\n')
    expect(rows.map(r => r.kind)).toEqual(['ctx', 'ctx', 'hunk', 'ctx', 'del', 'add'])
  })
})
