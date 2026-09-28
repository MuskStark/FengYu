import { beforeEach, describe, expect, it } from 'vitest'
import { useNavigationStore } from './navigation'
import { localeFor } from '../i18n'

describe('Email Center shell', () => {
  it('opens Compose and exposes six focused workspaces', () => {
    const store = useNavigationStore.getState()
    expect(store.active).toBe('compose')
    expect(store.items.map(item => item.id)).toEqual([
      'compose', 'batch', 'contacts', 'archive', 'records', 'accounts',
    ])
  })

  it('normalizes host locale to supported messages', () => {
    expect(localeFor('zh-CN')).toBe('zh')
    expect(localeFor('zh-TW')).toBe('zh')
    expect(localeFor('en-US')).toBe('en')
  })
})
