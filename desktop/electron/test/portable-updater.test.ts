import { describe, it, expect, vi, beforeEach } from 'vitest'

/**
 * Unit tests for the Windows portable self-updater (`src/updater/portable-updater.ts`).
 *
 * The network download, tar extraction, and bat spawn are real side-effects — we mock fetch and
 * fs/child_process and focus on the load-bearing logic: portable detection, version comparison,
 * release-asset parsing, and the explicit package marker / legacy uninstaller fallback.
 */

const PORTABLE_MARKER_EXISTS = { value: false }
const NSIS_UNINSTALLER_EXISTS = { value: false }
// When set, the next fake write stream fails its first write through BOTH channels a real
// stream uses (write-callback error + 'error' event); see makeFakeWriteStream.
const STREAM_WRITE_FAILS_WITH = { err: null as Error | null }
// Every fake write stream handed out by the mocked createWriteStream, newest last.
const fakeStreams: FakeWriteStream[] = []

interface FakeWriteStream {
  write: ReturnType<typeof vi.fn>
  end: ReturnType<typeof vi.fn>
  destroy: ReturnType<typeof vi.fn>
  once: ReturnType<typeof vi.fn>
  emit: (event: string, ...args: unknown[]) => void
}

/** Minimal fs.WriteStream double with just enough surface for downloadFile: write/end/destroy
 * plus a tiny once/emit pair so the 'error' wiring can be exercised. Callbacks fire async. */
function makeFakeWriteStream(): FakeWriteStream {
  const errorListeners: ((...args: unknown[]) => void)[] = []
  const failWith = STREAM_WRITE_FAILS_WITH.err
  const stream: FakeWriteStream = {
    write: vi.fn((_chunk: Buffer, cb?: (err?: Error | null) => void) => {
      if (!cb) return
      queueMicrotask(() => {
        if (failWith) {
          cb(failWith)
          stream.emit('error', failWith)
        } else {
          cb()
        }
      })
    }),
    end: vi.fn((cb?: () => void) => {
      if (cb) queueMicrotask(cb)
    }),
    destroy: vi.fn(),
    once: vi.fn((event: string, cb: (...args: unknown[]) => void) => {
      if (event === 'error') errorListeners.push(cb)
    }),
    emit: (event: string, ...args: unknown[]) => {
      if (event === 'error') for (const cb of [...errorListeners]) cb(...args)
    },
  }
  return stream
}

vi.mock('electron', () => ({
  app: {
    isPackaged: true,
    getVersion: vi.fn(() => '4.0.0-beta.1'),
    getPath: vi.fn((name: string) => (name === 'exe' ? 'C:\\Infinia\\Infinia.exe' : 'C:\\Temp')),
  },
}))
// update-log writes into runtimeRoot()/logs; pin the runtime root so the generated bat's LOG
// path is deterministic (and so tests never touch the real .fengyu tree).
vi.mock('../src/desktop/runtime-paths', () => ({
  runtimeRoot: () => 'C:\\Infinia\\.fengyu',
}))
vi.mock('node:child_process', () => ({
  spawn: vi.fn(() => ({ unref: vi.fn(), once: vi.fn() })),
  spawnSync: vi.fn(() => ({ status: 0, stdout: '', stderr: '' })),
}))
vi.mock('node:fs', () => ({
  existsSync: vi.fn((p: string) => {
    if (typeof p === 'string' && p.endsWith('fengyu-portable-zip')) return PORTABLE_MARKER_EXISTS.value
    if (typeof p === 'string' && p.endsWith('Uninstall Infinia.exe')) return NSIS_UNINSTALLER_EXISTS.value
    return false
  }),
  readdirSync: vi.fn(() => []),
  mkdirSync: vi.fn(),
  mkdtempSync: vi.fn(() => 'C:\\Temp\\fengyu-update'),
  createWriteStream: vi.fn(() => {
    const stream = makeFakeWriteStream()
    fakeStreams.push(stream)
    return stream
  }),
  writeFileSync: vi.fn(),
  appendFileSync: vi.fn(),
  rmSync: vi.fn(),
}))

beforeEach(() => {
  vi.clearAllMocks()
  PORTABLE_MARKER_EXISTS.value = false
  NSIS_UNINSTALLER_EXISTS.value = false
  STREAM_WRITE_FAILS_WITH.err = null
  fakeStreams.length = 0
  // process.resourcesPath is undefined outside a packaged Electron — pin it so existsSync joins work.
  ;(process as { resourcesPath?: string }).resourcesPath = 'C:\\Infinia\\resources'
})

describe('compareVersions', () => {
  it('orders pre-releases alpha < beta < rc < release', async () => {
    const { compareVersions } = await import('../src/updater/portable-updater')
    expect(compareVersions('4.0.0-alpha.1', '4.0.0-alpha.1')).toBe(0)
    expect(compareVersions('4.0.0-alpha.2', '4.0.0-alpha.1')).toBeGreaterThan(0)
    expect(compareVersions('4.0.0-beta.1', '4.0.0-alpha.9')).toBeGreaterThan(0)
    expect(compareVersions('4.0.0-rc.1', '4.0.0-beta.5')).toBeGreaterThan(0)
    expect(compareVersions('4.0.0', '4.0.0-rc.9')).toBeGreaterThan(0)
    expect(compareVersions('4.0.0-rc.1', '4.0.0')).toBeLessThan(0)
  })

  it('compares numeric segments, not lexicographically', async () => {
    const { compareVersions } = await import('../src/updater/portable-updater')
    expect(compareVersions('4.1.10', '4.1.9')).toBeGreaterThan(0)
    expect(compareVersions('4.10.0', '4.9.0')).toBeGreaterThan(0)
  })
})

describe('isWindowsPortable', () => {
  it('returns true when the packaged ZIP carries the explicit marker', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    PORTABLE_MARKER_EXISTS.value = true
    NSIS_UNINSTALLER_EXISTS.value = true
    const { isWindowsPortable } = await import('../src/updater/portable-updater')
    expect(isWindowsPortable()).toBe(true)
  })

  it('returns true for a legacy extracted ZIP even if electron-builder included app-update.yml', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    PORTABLE_MARKER_EXISTS.value = false
    NSIS_UNINSTALLER_EXISTS.value = false
    const { isWindowsPortable } = await import('../src/updater/portable-updater')
    expect(isWindowsPortable()).toBe(true)
  })

  it('returns false for an installed NSIS app with its uninstaller', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    NSIS_UNINSTALLER_EXISTS.value = true
    const { isWindowsPortable } = await import('../src/updater/portable-updater')
    expect(isWindowsPortable()).toBe(false)
  })

  it('returns false on non-win32 platforms', async () => {
    Object.defineProperty(process, 'platform', { value: 'darwin', configurable: true })
    const { isWindowsPortable } = await import('../src/updater/portable-updater')
    expect(isWindowsPortable()).toBe(false)
  })
})

describe('checkPortableUpdate', () => {
  it('returns the portable zip asset when the latest release is newer', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    const fakeRelease = {
      tag_name: 'v4.0.0-beta.2',
      name: 'Infinia 4.0.0-beta.2',
      html_url: 'https://github.com/MuskStark/FengYu/releases/tag/v4.0.0-beta.2',
      assets: [
        { name: 'Infinia-4.0.0-beta.2-win-x64-setup.exe', browser_download_url: 'https://x/setup.exe' },
        { name: 'Infinia-4.0.0-beta.2-win32-x64-portable.zip', browser_download_url: 'https://x/portable.zip', digest: `sha256:${'a'.repeat(64)}` },
      ],
    }
    const fakeFetch = vi.fn(async () => ({
      ok: true,
      json: async () => [fakeRelease],
    })) as unknown as typeof fetch

    const { checkPortableUpdate } = await import('../src/updater/portable-updater')
    const result = await checkPortableUpdate('MuskStark/FengYu', fakeFetch)

    expect(result).not.toBeNull()
    expect(result!.version).toBe('4.0.0-beta.2')
    expect(result!.zipUrl).toBe('https://x/portable.zip')
    expect(result!.sha256).toBe('a'.repeat(64))
    expect(result!.releaseUrl).toContain('v4.0.0-beta.2')
  })

  it('returns null when the latest release is not newer than current', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    const { app } = await import('electron')
    ;(app.getVersion as ReturnType<typeof vi.fn>).mockReturnValue('4.0.0')
    const fakeRelease = {
      tag_name: 'v4.0.0',
      name: 'Infinia 4.0.0',
      html_url: 'https://github.com/MuskStark/FengYu/releases/tag/v4.0.0',
      assets: [{ name: 'Infinia-4.0.0-win32-x64-portable.zip', browser_download_url: 'https://x/p.zip' }],
    }
    const fakeFetch = vi.fn(async () => ({ ok: true, json: async () => [fakeRelease] })) as unknown as typeof fetch
    const { checkPortableUpdate } = await import('../src/updater/portable-updater')
    expect(await checkPortableUpdate('MuskStark/FengYu', fakeFetch)).toBeNull()
    // restore for subsequent tests
    ;(app.getVersion as ReturnType<typeof vi.fn>).mockReturnValue('4.0.0-beta.1')
  })

  it('returns null when no portable zip asset is present', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    const fakeRelease = {
      tag_name: 'v5.0.0',
      name: 'Infinia 5.0.0',
      html_url: 'https://github.com/MuskStark/FengYu/releases/tag/v5.0.0',
      assets: [{ name: 'Infinia-5.0.0-win-x64-setup.exe', browser_download_url: 'https://x/setup.exe' }],
    }
    const fakeFetch = vi.fn(async () => ({ ok: true, json: async () => [fakeRelease] })) as unknown as typeof fetch
    const { checkPortableUpdate } = await import('../src/updater/portable-updater')
    expect(await checkPortableUpdate('MuskStark/FengYu', fakeFetch)).toBeNull()
  })

  it('throws when the GitHub API is not ok', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    const fakeFetch = vi.fn(async () => ({ ok: false, status: 403 })) as unknown as typeof fetch
    const { checkPortableUpdate } = await import('../src/updater/portable-updater')
    await expect(checkPortableUpdate('MuskStark/FengYu', fakeFetch)).rejects.toThrow(/HTTP 403/)
  })

  it('accepts a single release object from FY-Proxy (intranet mirror) instead of a GitHub array', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    // FY-Proxy returns one release object (not wrapped in an array), with download URLs pointing
    // at the intranet server. checkPortableUpdate must accept both shapes.
    const fakeRelease = {
      tag_name: 'v4.0.0',
      name: 'Infinia 4.0.0',
      html_url: 'http://10.0.0.5:8088/admin',
      assets: [
        { name: 'Infinia-4.0.0-win32-x64-portable.zip', browser_download_url: 'http://10.0.0.5:8088/fengyu-releases/download/Infinia-4.0.0-win32-x64-portable.zip', digest: `sha256:${'b'.repeat(64)}` },
      ],
    }
    const fakeFetch = vi.fn(async () => ({ ok: true, json: async () => fakeRelease })) as unknown as typeof fetch
    const { checkPortableUpdate } = await import('../src/updater/portable-updater')
    const result = await checkPortableUpdate('MuskStark/FengYu', fakeFetch)

    expect(result).not.toBeNull()
    expect(result!.version).toBe('4.0.0')
    expect(result!.zipUrl).toContain('10.0.0.5:8088')
  })

  it('uses FENGYU_UPDATE_API_BASE when set (intranet mirror)', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    process.env.FENGYU_UPDATE_API_BASE = 'http://10.0.0.5:8088/'
    const fakeFetch = vi.fn(async () => ({
      ok: true,
      json: async () => [], // no releases
    })) as unknown as typeof fetch
    try {
      const { checkPortableUpdate } = await import('../src/updater/portable-updater')
      await checkPortableUpdate('MuskStark/FengYu', fakeFetch)
      // Verify the URL used the intranet base (with trailing slash trimmed), not GitHub
      expect((fakeFetch as ReturnType<typeof vi.fn>).mock.calls[0][0]).toBe(
        'http://10.0.0.5:8088/fengyu-releases/api/releases/latest?channel=windows-portable',
      )
    } finally {
      delete process.env.FENGYU_UPDATE_API_BASE
    }
  })

  it('rejects FY-Proxy portable metadata without a valid SHA-256 digest', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    process.env.FENGYU_UPDATE_API_BASE = 'http://10.0.0.5:8088'
    const fakeRelease = {
      tag_name: 'v4.0.0',
      name: 'Infinia 4.0.0',
      html_url: 'http://10.0.0.5:8088/files',
      assets: [{
        name: 'Infinia-4.0.0-win32-x64-portable.zip',
        browser_download_url: 'http://10.0.0.5:8088/fengyu-releases/download/portable.zip',
      }],
    }
    const fakeFetch = vi.fn(async () => ({ ok: true, json: async () => fakeRelease })) as unknown as typeof fetch
    try {
      const { checkPortableUpdate } = await import('../src/updater/portable-updater')
      await expect(checkPortableUpdate('MuskStark/FengYu', fakeFetch)).rejects.toThrow(/SHA-256 digest/)
    } finally {
      delete process.env.FENGYU_UPDATE_API_BASE
    }
  })

  it('refuses a plain-HTTP artifact without a digest even off the FY-Proxy channel', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    // No FENGYU_UPDATE_API_BASE: the generic gate must still refuse http:// artifacts that
    // publish no digest, because the bytes are tamperable in transit.
    const fakeRelease = {
      tag_name: 'v4.0.0',
      name: 'Infinia 4.0.0',
      html_url: 'https://github.com/MuskStark/FengYu/releases/tag/v4.0.0',
      assets: [{
        name: 'Infinia-4.0.0-win32-x64-portable.zip',
        browser_download_url: 'http://mirror.example.org/portable.zip',
      }],
    }
    const fakeFetch = vi.fn(async () => ({ ok: true, json: async () => [fakeRelease] })) as unknown as typeof fetch
    const { checkPortableUpdate } = await import('../src/updater/portable-updater')
    await expect(checkPortableUpdate('MuskStark/FengYu', fakeFetch)).rejects.toThrow(
      /plain HTTP without a SHA-256 digest/,
    )
  })

  it('keeps an HTTPS artifact digest-optional (GitHub status quo)', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    const fakeRelease = {
      tag_name: 'v4.0.0',
      name: 'Infinia 4.0.0',
      html_url: 'https://github.com/MuskStark/FengYu/releases/tag/v4.0.0',
      // GitHub's API does not publish asset digests; https downloads stay allowed without one.
      assets: [{ name: 'Infinia-4.0.0-win32-x64-portable.zip', browser_download_url: 'https://x/portable.zip' }],
    }
    const fakeFetch = vi.fn(async () => ({ ok: true, json: async () => [fakeRelease] })) as unknown as typeof fetch
    const { checkPortableUpdate } = await import('../src/updater/portable-updater')
    const result = await checkPortableUpdate('MuskStark/FengYu', fakeFetch)
    expect(result).not.toBeNull()
    expect(result!.sha256).toBeNull()
  })
})

describe('applyPortableUpdate — replace-bat contract', () => {
  /**
   * The bat is the whole portable update engine once the shell exits; regressions here show up
   * in the field as an install stuck on a "find <pid>" console. Pin the load-bearing lines:
   * script lives in the app root (never %TEMP%), logs to .fengyu/logs/update.log, launches
   * through a hidden wscript/.vbs (a visible console froze the script via QuickEdit in the
   * field), PID+image wait, force-kill backstop, console-free delay, bounded robocopy retries.
   */
  it('writes the script into the app root, launches it hidden via wscript, and logs every step', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    process.env.SystemRoot = 'C:\\Windows'
    const { applyPortableUpdate } = await import('../src/updater/portable-updater')
    applyPortableUpdate('C:\\Infinia\\.fengyu\\update-staging-x\\extracted')

    const fs = await import('node:fs')
    const writes = (fs.writeFileSync as ReturnType<typeof vi.fn>).mock.calls as [string, string, string?][]
    const batCall = writes.find(([p]) => String(p).endsWith('.bat'))
    const vbsCall = writes.find(([p]) => String(p).endsWith('.vbs'))
    expect(batCall).toBeDefined()
    expect(vbsCall).toBeDefined()
    // Script + launcher in the portable app root, NOT %TEMP% (intranet/EDR machines block temp
    // scripts, and a temp log is cleaned before anyone can read it).
    expect(batCall![0]).toBe('C:\\Infinia\\fengyu-portable-update.bat')
    expect(vbsCall![0]).toBe('C:\\Infinia\\fengyu-portable-update.vbs')
    const bat = batCall![1]

    // The vbs launcher runs the bat with a HIDDEN window (0): a visible console got clicked in
    // the field, QuickEdit suspended its conhost, and every console child of the script froze.
    // UTF-16 + BOM so non-ASCII app paths survive wscript's ANSI default.
    expect(vbsCall![1]).toContain('CreateObject("WScript.Shell").Run')
    expect(vbsCall![1]).toContain('"cmd.exe /c ""C:\\Infinia\\fengyu-portable-update.bat""", 0, False')
    expect(vbsCall![2]).toBe('utf16le')

    // All script steps append to the app's update.log — same file the main-process stages use.
    expect(bat).toContain('set "LOG=C:\\Infinia\\.fengyu\\logs\\update.log"')
    expect((bat.match(/>> "%LOG%"/g) || []).length).toBeGreaterThanOrEqual(6)

    // Match on PID AND image name so a reused PID can't pin the wait loop; absolute paths so a
    // PATH-shadowing find.exe (Git for Windows) can't break the match.
    expect(bat).toContain(
      `%SystemRoot%\\System32\\tasklist.exe /FI "PID eq ${process.pid}" /FI "IMAGENAME eq Infinia.exe"`,
    )
    expect(bat).toContain(`%SystemRoot%\\System32\\find.exe "${process.pid}"`)
    // The wait is bounded: a stuck old process (field-confirmed zombie) is force-killed after
    // ~15 polls, not waited on forever.
    expect(bat).toContain('if %tries% geq 15')
    expect(bat).toContain(`%SystemRoot%\\System32\\taskkill.exe /F /T /PID ${process.pid}`)
    // ping works without a console; timeout.exe errors immediately when the bat is spawned
    // with a hidden/detached console and would busy-spin the loop.
    expect(bat).toContain('ping -n 2 127.0.0.1')
    expect(bat).not.toContain('timeout /t')
    // robocopy's default is a million retries x 30s — a locked file must fail fast instead.
    expect(bat).toContain('/R:5 /W:2')
    expect(bat).toContain('robocopy')
    expect(bat).toContain('start "" "C:\\Infinia\\Infinia.exe"')
    // The script cleans up both itself and the vbs launcher.
    expect(bat).toContain('del "%~dp0fengyu-portable-update.vbs" 2>nul')

    const cp = await import('node:child_process')
    expect(cp.spawn).toHaveBeenCalledWith(
      'C:\\Windows\\System32\\wscript.exe',
      ['//B', '//Nologo', 'C:\\Infinia\\fengyu-portable-update.vbs'],
      { detached: true, windowsHide: true, shell: false, stdio: 'ignore' },
    )
  })

  it('falls back to a direct cmd spawn when wscript cannot be spawned', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    process.env.SystemRoot = 'C:\\Windows'
    const cp = await import('node:child_process')
    // First spawn (wscript) errors asynchronously; the fallback spawns cmd directly.
    ;(cp.spawn as ReturnType<typeof vi.fn>).mockImplementationOnce(() => {
      const child = { unref: vi.fn(), once: vi.fn((event: string, cb: () => void) => {
        if (event === 'error') queueMicrotask(cb)
        return child
      }) }
      return child
    })
    const { applyPortableUpdate } = await import('../src/updater/portable-updater')
    applyPortableUpdate('C:\\Infinia\\.fengyu\\update-staging-x\\extracted')

    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(cp.spawn).toHaveBeenCalledWith(
      'cmd',
      ['/c', 'C:\\Infinia\\fengyu-portable-update.bat'],
      { detached: true, windowsHide: true, shell: false, stdio: 'ignore' },
    )
    delete process.env.SystemRoot
  })
})

describe('downloadAndExtractPortable — download hardening (512 MB cap + stream errors)', () => {
  const INFO = {
    version: '4.0.0',
    zipUrl: 'https://example.com/Infinia-4.0.0-win32-x64-portable.zip',
    sha256: null,
    releaseUrl: 'https://example.com/rel',
    releaseName: 'Infinia 4.0.0',
  }

  /** fetch double whose single GET serves `chunks` sequentially; exposes the body reader + cancel. */
  function fakeDownloadFetch(opts: { contentLength?: string | null; chunks?: Uint8Array[] }) {
    const reads: { done: boolean; value?: Uint8Array }[] = (opts.chunks ?? []).map((value) => ({ done: false, value }))
    reads.push({ done: true })
    const reader = {
      read: vi.fn(async () => reads.shift() ?? { done: true }),
      cancel: vi.fn(async () => {}),
    }
    const bodyCancel = vi.fn(async () => {})
    const fetchFn = vi.fn(async () => ({
      ok: true,
      headers: {
        get: (name: string) => (name.toLowerCase() === 'content-length' ? opts.contentLength ?? null : null),
      },
      body: { getReader: () => reader, cancel: bodyCancel },
    })) as unknown as typeof fetch
    return { fetchFn, reader, bodyCancel }
  }

  it('refuses an over-cap Content-Length before streaming anything', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    const { fetchFn, reader, bodyCancel } = fakeDownloadFetch({
      contentLength: String(600 * 1024 * 1024),
      chunks: [new Uint8Array(8)],
    })
    const { downloadAndExtractPortable } = await import('../src/updater/portable-updater')

    await expect(downloadAndExtractPortable(INFO, () => {}, fetchFn)).rejects.toThrow(/exceeds the 512 MB cap/)

    const fs = await import('node:fs')
    expect(reader.read).not.toHaveBeenCalled() // nothing streamed
    expect(bodyCancel).toHaveBeenCalled() // the connection is released best-effort
    expect(fs.createWriteStream).not.toHaveBeenCalled() // no staging file ever opened
  })

  it('aborts mid-flight when the received bytes cross the cap (absent/lying Content-Length)', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    // A Uint8Array subclass that reports a 600 MB byteLength without allocating it.
    class FatChunk extends Uint8Array {
      override get byteLength(): number {
        return 600 * 1024 * 1024
      }
    }
    const { fetchFn, reader } = fakeDownloadFetch({ contentLength: null, chunks: [new Uint8Array(10), new FatChunk(4)] })
    const { downloadAndExtractPortable } = await import('../src/updater/portable-updater')

    await expect(downloadAndExtractPortable(INFO, () => {}, fetchFn)).rejects.toThrow(/exceeds the 512 MB cap/)

    expect(reader.read).toHaveBeenCalledTimes(2) // the fat chunk was read, then the download aborted
    expect(reader.cancel).toHaveBeenCalled() // transfer aborted, not drained
    expect(fakeStreams.length).toBe(1)
    expect(fakeStreams[0].write).toHaveBeenCalledTimes(1) // only the first (under-cap) chunk hit disk
    const fs = await import('node:fs')
    const { join } = await import('node:path')
    expect(fs.rmSync).toHaveBeenCalledWith(join('C:\\Temp\\fengyu-update', 'portable.zip'), { force: true })
  })

  it('rejects and deletes the staging zip when the write stream fails', async () => {
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    STREAM_WRITE_FAILS_WITH.err = new Error('ENOSPC: no space left on device')
    const { fetchFn, reader } = fakeDownloadFetch({ contentLength: '10', chunks: [new Uint8Array(10)] })
    const { downloadAndExtractPortable } = await import('../src/updater/portable-updater')

    await expect(downloadAndExtractPortable(INFO, () => {}, fetchFn)).rejects.toThrow(/ENOSPC/)

    // A write failure falls back to the manual-update path as a rejection (not an unhandled
    // stream 'error' event) and leaves no half-written zip behind.
    expect(reader.cancel).toHaveBeenCalled()
    expect(fakeStreams[0].destroy).toHaveBeenCalled()
    const fs = await import('node:fs')
    const { join } = await import('node:path')
    expect(fs.rmSync).toHaveBeenCalledWith(join('C:\\Temp\\fengyu-update', 'portable.zip'), { force: true })
  })
})
