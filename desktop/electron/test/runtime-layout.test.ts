import { describe, it, expect } from 'vitest'
import { join } from 'node:path'
import { resolveLayout } from '../src/backend/runtime-layout'

describe('resolveLayout', () => {
  it('resolves the jar under the packaged resource dir', () => {
    const layout = resolveLayout(true, '/app/resources', {})
    // resolveLayout picks posix/win32 path per host platform (runtime-layout.ts:36),
    // so the expected paths must follow the host separator too — assert via join()
    // rather than hard-coded forward slashes (which fail on Windows CI). The bundled
    // java binary also gets a platform suffix (java.exe on win32, java elsewhere).
    const javaName = process.platform === 'win32' ? 'java.exe' : 'java'
    expect(layout.jar).toBe(join('/app/resources', 'binaries', 'FengYu.jar'))
    expect(layout.jre).toBe(join('/app/resources', 'jre', 'bin', javaName))
    // Official plugins are store-distributed — the layout carries no plugins dir.
    expect('plugins' in layout).toBe(false)
  })

  it('resolves the jar from FENGYU_JAR env in dev', () => {
    const layout = resolveLayout(false, '/unused', {
      FENGYU_JAR: '/local/FengYu.jar',
    })
    // Env-supplied paths are passed through verbatim (no path.join on them),
    // so they keep whatever separator the caller used.
    expect(layout.jar).toBe('/local/FengYu.jar')
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
