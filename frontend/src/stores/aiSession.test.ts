import { describe, expect, it, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { toChatHistory, guessPluginForFile, type ChatTurn } from './aiSession'
import type { PluginFileRef } from '@/api/types'
import { useAiSessionStore } from './aiSession'

function turn(role: ChatTurn['role'], content: string, streaming = false): ChatTurn {
  return {
    id: 1,
    role,
    content,
    thinking: '',
    streaming,
    confirmations: [],
  }
}

describe('AI session history', () => {
  it('does not send the streaming assistant placeholder to the model', () => {
    expect(toChatHistory([
      turn('user', 'previous'),
      turn('assistant', 'answer'),
      turn('user', 'current'),
      turn('assistant', '', true),
    ])).toEqual([
      { role: 'user', content: 'previous' },
      { role: 'assistant', content: 'answer' },
      { role: 'user', content: 'current' },
    ])
  })
})

describe('plugin guess from file name', () => {
  it('maps xlsx to excel', () => {
    expect(guessPluginForFile('report.xlsx')).toBe('fan.summer.excel')
  })
  it('maps py to offlinepython', () => {
    expect(guessPluginForFile('main.py')).toBe('fan.summer.offlinepython')
  })
  it('returns empty for unknown extensions', () => {
    expect(guessPluginForFile('notes.txt')).toBe('')
  })
})

describe('AI session active files', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('addActiveFile replaces same-plugin same-name entries', async () => {
    const store = useAiSessionStore()
    const ref1: PluginFileRef = { id: 'ref_1', name: 'report.xlsx', kind: 'file', access: 'read', size: 10 }
    const ref2: PluginFileRef = { id: 'ref_2', name: 'report.xlsx', kind: 'file', access: 'read', size: 20 }
    store.addActiveFile('fan.summer.excel', ref1)
    store.addActiveFile('fan.summer.excel', ref2)
    expect(store.activeFiles.length).toBe(1)
    expect(store.activeFiles[0].ref.id).toBe('ref_2')
  })

  it('removeActiveFile removes by pluginId + refId', () => {
    const store = useAiSessionStore()
    const ref: PluginFileRef = { id: 'ref_x', name: 'f', kind: 'file', access: 'read', size: 1 }
    store.addActiveFile('fan.summer.excel', ref)
    store.removeActiveFile('fan.summer.excel', 'ref_x')
    expect(store.activeFiles.length).toBe(0)
  })

  it('sendableFileRefs omits entries with empty pluginId', () => {
    const store = useAiSessionStore()
    const ref: PluginFileRef = { id: 'ref_y', name: 'f', kind: 'file', access: 'read', size: 1 }
    store.addActiveFile('', ref) // user has not chosen a plugin
    expect(store.sendableFileRefs()).toEqual([])
  })
})
