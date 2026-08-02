import { describe, expect, it, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { toChatHistory, guessPluginForFile, grantAccessForAttachment, type ChatTurn } from './aiSession'
import type { PluginDescriptor, PluginFileRef } from '@/api/types'
import { useAiSessionStore } from './aiSession'
import { api } from '@/api/client'

function turn(role: ChatTurn['role'], content: string, streaming = false): ChatTurn {
  return {
    id: 1,
    role,
    content,
    thinking: '',
    streaming,
    confirmations: [],
    activities: [],
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

describe('AI attachment grant access', () => {
  it('keeps files read-only and makes selected directories valid output targets', () => {
    expect(grantAccessForAttachment('file')).toBe('read')
    expect(grantAccessForAttachment('directory')).toBe('read-write')
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

  it('clears and revokes attachments when switching conversations', async () => {
    const revoke = vi.spyOn(api, 'revokeAiFile').mockResolvedValue()
    const store = useAiSessionStore()
    const first = store.newConversation().id
    store.newConversation()
    store.addActiveFile('fan.summer.excel', {
      id: 'ref_private', name: 'private.xlsx', kind: 'file', access: 'read', size: 1,
    })

    await store.select(first)

    expect(store.activeFiles).toEqual([])
    expect(revoke).toHaveBeenCalledWith('fan.summer.excel', 'ref_private')
  })
})

describe('AI session installed plugins (plugin-picker source)', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('loadInstalledPlugins caches the plugin descriptor list', async () => {
    const store = useAiSessionStore()
    expect(store.installedPlugins).toEqual([])
    const excel: PluginDescriptor = {
      id: 'fan.summer.excel', name: 'Excel', description: '', category: 'OTHER',
      icon: '', iconStyle: '', version: '1', uiEntry: '', supportsAi: true, source: 'OFFICIAL',
      enabled: true, permissions: ['files.read'],
    }
    const python: PluginDescriptor = {
      id: 'fan.summer.offlinepython', name: 'Python', description: '', category: 'OTHER',
      icon: '', iconStyle: '', version: '1', uiEntry: '', supportsAi: true, source: 'OFFICIAL',
      enabled: true, permissions: ['files.read', 'files.write'],
    }
    const noPerm: PluginDescriptor = {
      id: 'fan.summer.markdown', name: 'Markdown', description: '', category: 'OTHER',
      icon: '', iconStyle: '', version: '1', uiEntry: '', supportsAi: false, source: 'OFFICIAL',
      enabled: true, permissions: [],
    }
    vi.spyOn(api, 'getPlugins').mockResolvedValue([excel, python, noPerm])
    await store.loadInstalledPlugins()
    expect(store.installedPlugins.map((p) => p.id)).toEqual([
      'fan.summer.excel', 'fan.summer.offlinepython', 'fan.summer.markdown',
    ])
    // call site filters to plugins declaring files.read when building picker options
    expect(store.installedPlugins.filter((p) => (p.permissions ?? []).includes('files.read')).map((p) => p.id))
      .toEqual(['fan.summer.excel', 'fan.summer.offlinepython'])
  })
})
