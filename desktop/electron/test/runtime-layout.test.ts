import { describe, it, expect } from 'vitest'
import { resolveLayout } from '../src/backend/runtime-layout'

describe('resolveLayout', () => {
  it('resolves jar + plugins under the packaged resource dir', () => {
    const layout = resolveLayout(true, '/app/resources', {})
    expect(layout.jar).toBe('/app/resources/binaries/FengYu.jar')
    expect(layout.plugins).toBe('/app/resources/plugins')
    expect(layout.jre).toBe('/app/resources/jre/bin/java')
  })

  it('resolves jar + plugins from FENGYU_JAR env in dev', () => {
    const layout = resolveLayout(false, '/unused', {
      FENGYU_JAR: '/local/FengYu.jar',
      FENGYU_PLUGINS: '/local/plugins',
    })
    expect(layout.jar).toBe('/local/FengYu.jar')
    expect(layout.plugins).toBe('/local/plugins')
    expect(layout.jre).toBeUndefined()
  })

  it('appends .exe to the bundled java on Windows', () => {
    const originalPlatform = Object.getOwnPropertyDescriptor(process, 'platform')
    Object.defineProperty(process, 'platform', { value: 'win32' })
    try {
      const layout = resolveLayout(true, 'C:\\app\\resources', {})
      expect(layout.jre).toBe('C:\\app\\resources\\jre\\bin\\java.exe')
    } finally {
      if (originalPlatform) Object.defineProperty(process, 'platform', originalPlatform)
    }
  })
})
