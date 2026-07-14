import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { localeFor } from '../i18n'
import { useNavigationStore } from './navigation'

beforeEach(() => setActivePinia(createPinia()))

describe('Email Center shell', () => {
  it('opens Compose and exposes six focused workspaces', () => {
    const store = useNavigationStore()
    expect(store.active).toBe('compose')
    expect(store.items.map(item => item.id)).toEqual([
      'compose', 'batch', 'contacts', 'archive', 'records', 'accounts',
    ])
  })

  it('normalizes host locale to supported messages', () => {
    expect(localeFor('zh-CN')).toBe('zh-CN')
    expect(localeFor('zh-TW')).toBe('zh-CN')
    expect(localeFor('en-US')).toBe('en')
  })
})
