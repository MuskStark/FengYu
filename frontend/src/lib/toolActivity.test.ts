import { describe, expect, it } from 'vitest'
import { applyToolActivity, activityLabel, diffLines, todosFromOutput, parseToolOutput, type ToolActivity } from './toolActivity'

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

describe('4.1.0 activity model: args/output capture + todo extraction', () => {
  it('captures arguments at call time and the bounded raw output at result time', () => {
    const items: ToolActivity[] = []
    applyToolActivity(items, { phase: 'call', id: 'x1', name: 'grep',
      arguments: { pattern: 'TODO', path: 'src' } })
    applyToolActivity(items, { phase: 'result', id: 'x1', success: true,
      output: '{"success":true,"count":3,"matches":[]}' })
    expect(items[0].args).toEqual({ pattern: 'TODO', path: 'src' })
    expect(items[0].output).toContain('"count":3')
  })

  it('extracts the error message of a failed result envelope', () => {
    const items: ToolActivity[] = []
    applyToolActivity(items, { phase: 'call', id: 'x2', name: 'read_file', arguments: { path: 'a' } })
    applyToolActivity(items, { phase: 'result', id: 'x2', success: false,
      output: '{"success":false,"error":"File appears to be binary"}' })
    expect(items[0].status).toBe('failed')
    expect(items[0].error).toBe('File appears to be binary')
  })

  it('clips outsized outputs so the transcript stays light', () => {
    const items: ToolActivity[] = []
    applyToolActivity(items, { phase: 'call', id: 'x3', name: 'read_file', arguments: { path: 'a' } })
    applyToolActivity(items, { phase: 'result', id: 'x3', success: true, output: 'y'.repeat(20_000) })
    expect(items[0].output.length).toBeLessThanOrEqual(8_000)
  })

  it('parses the todo checklist out of a todo_write result', () => {
    const steps = todosFromOutput(JSON.stringify({
      success: true,
      todos: [
        { content: 'scan files', status: 'completed' },
        { content: 'fix bug', status: 'in_progress' },
        { content: 'verify', status: 'pending' },
      ],
    }))
    expect(steps).toEqual([
      { content: 'scan files', status: 'completed' },
      { content: 'fix bug', status: 'in_progress' },
      { content: 'verify', status: 'pending' },
    ])
    expect(todosFromOutput('not json')).toBeNull()
    expect(todosFromOutput('{"success":true}')).toBeNull()
  })

  it('labels the 4.1.0 tools (exec variants, explore, todo)', () => {
    expect(activityLabel('workspace_exec', { command: 'git status' })).toBe('Run git status')
    expect(activityLabel('explore', { task: 'find the auth flow' })).toContain('find the auth flow')
    expect(activityLabel('todo_write', {})).toBeTruthy()
  })

  it('keeps non-JSON outputs out of parseToolOutput', () => {
    expect(parseToolOutput('plain text')).toBeNull()
    expect(parseToolOutput('[1,2]')).toBeNull()
    expect(parseToolOutput('{"a":1}')).toEqual({ a: 1 })
  })
})
