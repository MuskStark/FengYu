import { EventEmitter } from 'node:events'
import { spawn as realSpawn, type ChildProcess } from 'node:child_process'
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync, mkdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  MIN_CACHE_BYTES,
  aotFlagsForVersion,
  parseJavaVersionOutput,
  parseJvmReleaseVersion,
  prepareAotLaunch,
  readJreVersion,
  readMetaFile,
  resolveLaunchPlan,
  trainAotCacheInBackground,
  type TrainingContext,
} from '../src/backend/aot-cache'

const ZULU_25_VERSION_OUTPUT = [
  'openjdk version "25.0.4.1" 2026-08-18 LTS',
  'OpenJDK Runtime Environment Zulu25.36+205-CA (build 25.0.4.1+1-LTS)',
  'OpenJDK 64-Bit Server VM Zulu25.36+205-CA (build 25.0.4.1+1-LTS, mixed mode, sharing)',
].join('\n')

// Real-shape boot log lines the mode gate matches on (see train-aot-cache.sh: the
// "Started <main class>" line is HeadlessLauncher for BOTH modes and cannot gate).
const APP_MODE_BOOT_LOG = [
  '2026-10-09T11:19:26.100  INFO [main] o.f.core.internal.command.DbValidate - Successfully validated 2 migrations (execution time 00:00.010s)',
  '2026-10-09T11:19:27.100  INFO [main] o.s.o.j.LocalContainerEntityManagerFactoryBean - Initialized JPA EntityManagerFactory for persistence unit \'default\'',
  '2026-10-09T11:19:27.140  INFO [main] fan.summer.fengyu.HeadlessLauncher - Started HeadlessLauncher in 1.417 seconds (process running for 1.786)',
].join('\n')
// A SETUP-mode ready line: proves readiness alone must not pass the gate.
const SETUP_MODE_BOOT_LOG = 'INFO [main] fan.summer.fengyu.HeadlessLauncher - Started HeadlessLauncher in 0.931 seconds'

describe('aotFlagsForVersion', () => {
  it('maps JDK 24+ to the Leyden AOT flag pair', () => {
    expect(aotFlagsForVersion(25)).toEqual({
      useFlag: '-XX:AOTCache',
      dumpFlag: '-XX:AOTCacheOutput',
    })
  })

  it('maps JDK 13–23 to the dynamic AppCDS pair', () => {
    expect(aotFlagsForVersion(21)).toEqual({
      useFlag: '-XX:SharedArchiveFile',
      dumpFlag: '-XX:ArchiveClassesAtExit',
    })
  })

  it('refuses JDK 12 and older — no dump support, must run uncached', () => {
    expect(aotFlagsForVersion(12)).toBeNull()
    expect(aotFlagsForVersion(8)).toBeNull()
  })
})

describe('parseJavaVersionOutput', () => {
  it('parses a modern vendor build', () => {
    const info = parseJavaVersionOutput(ZULU_25_VERSION_OUTPUT)
    expect(info).not.toBeNull()
    expect(info!.major).toBe(25)
    expect(info!.line).toContain('openjdk version "25.0.4.1"')
  })

  it('parses the legacy 1.8 numbering into major 8', () => {
    const info = parseJavaVersionOutput('openjdk version "1.8.0_392"')
    expect(info).not.toBeNull()
    expect(info!.major).toBe(8)
  })

  it('returns null on unparseable output rather than guessing flags', () => {
    // A wrong major would select an unrecognized -XX flag, which makes the JVM refuse
    // to start — null must propagate to "no cache" instead.
    expect(parseJavaVersionOutput('Error: could not open')).toBeNull()
  })
})

describe('parseJvmReleaseVersion', () => {
  it('extracts the major from a jlink release value', () => {
    expect(parseJvmReleaseVersion('25.0.4.1')).toBe(25)
    expect(parseJvmReleaseVersion('21.0.12.1')).toBe(21)
    expect(parseJvmReleaseVersion('1.8.0_392')).toBe(8)
  })

  it('returns 0 when the value is garbage', () => {
    expect(parseJvmReleaseVersion('')).toBe(0)
    expect(parseJvmReleaseVersion('x.y')).toBe(0)
  })
})

describe('resolveLaunchPlan', () => {
  const base = {
    appVersion: '4.1.0-alpha.1',
    jarPath: '/Applications/Infinia.app/Contents/Resources/binaries/FengYu.jar',
    jarBytes: 123456,
  }

  it('uses a matching bundled CI cache with the exact relative-classpath form', () => {
    const plan = resolveLaunchPlan({
      ...base,
      bundled: {
        cachePath: '/Applications/Infinia.app/Contents/Resources/jre/FengYu.aot',
        meta: { kind: 'ci', appVersion: base.appVersion, jvmVersion: '25.0.4.1', jarBytes: base.jarBytes },
        exists: true,
        jreVersion: '25.0.4.1',
      },
      self: { cachePath: '/x', meta: null, exists: false, javaVersion: null },
    })
    expect(plan).toMatchObject({
      mode: 'use',
      source: 'bundled',
      classpath: 'FengYu.jar',
      cwd: '/Applications/Infinia.app/Contents/Resources/binaries',
    })
    if (plan.mode !== 'use') throw new Error('unreachable')
    expect(plan.flags).toContain('-XX:AOTCache=/Applications/Infinia.app/Contents/Resources/jre/FengYu.aot')
  })

  it('rejects a bundled cache whose jar or JRE identity drifted (post-update safety)', () => {
    const plan = resolveLaunchPlan({
      ...base,
      bundled: {
        cachePath: '/r/jre/FengYu.aot',
        meta: { kind: 'ci', appVersion: '4.0.9', jvmVersion: '25.0.4.1', jarBytes: base.jarBytes },
        exists: true,
        jreVersion: '25.0.4.1',
      },
      self: { cachePath: '/x', meta: null, exists: false, javaVersion: null },
    })
    expect(plan.mode).toBe('none')
    expect(plan.canTrain).toBe(true) // bundled JRE 25 → training is possible
  })

  it('uses a matching self-trained cache with the absolute classpath and the version-mapped flag', () => {
    const line = 'openjdk version "21.0.12.1" 2024-10-15 LTS'
    const plan = resolveLaunchPlan({
      ...base,
      self: {
        cachePath: '/root/.fengyu/aot-cache/FengYu.aot',
        meta: { kind: 'self', appVersion: base.appVersion, jvmVersion: line, jarBytes: base.jarBytes },
        exists: true,
        javaVersion: { major: 21, line },
      },
    })
    expect(plan).toMatchObject({ mode: 'use', source: 'self', classpath: base.jarPath })
    if (plan.mode !== 'use') throw new Error('unreachable')
    expect(plan.flags).toContain('-XX:SharedArchiveFile=/root/.fengyu/aot-cache/FengYu.aot')
  })

  it('rejects a self cache trained by a different JVM (lite installs switching java)', () => {
    const plan = resolveLaunchPlan({
      ...base,
      self: {
        cachePath: '/root/.fengyu/aot-cache/FengYu.aot',
        meta: { kind: 'self', appVersion: base.appVersion, jvmVersion: 'old line', jarBytes: base.jarBytes },
        exists: true,
        javaVersion: { major: 25, line: 'new line' },
      },
    })
    expect(plan).toEqual({ mode: 'none', canTrain: true })
  })

  it('marks an ancient PATH java as untrainable', () => {
    const plan = resolveLaunchPlan({
      ...base,
      self: { cachePath: '/x', meta: null, exists: false, javaVersion: { major: 11, line: '11.0.2' } },
    })
    expect(plan).toEqual({ mode: 'none', canTrain: false })
  })

  it('rejects a self-kind meta in the bundled slot (and vice versa)', () => {
    // A relative-classpath CI cache must never be consumed through the absolute
    // classpath form (it would silently miss), nor a self cache through the relative
    // one — the kind check is what keeps the two spawn forms apart.
    const bundledSlot = resolveLaunchPlan({
      ...base,
      bundled: {
        cachePath: '/r/jre/FengYu.aot',
        meta: { kind: 'self', appVersion: base.appVersion, jvmVersion: '25.0.4.1', jarBytes: base.jarBytes },
        exists: true,
        jreVersion: '25.0.4.1',
      },
      self: { cachePath: '/x', meta: null, exists: false, javaVersion: null },
    })
    expect(bundledSlot.mode).toBe('none')

    const selfSlot = resolveLaunchPlan({
      ...base,
      self: {
        cachePath: '/root/.fengyu/aot-cache/FengYu.aot',
        meta: { kind: 'ci', appVersion: base.appVersion, jvmVersion: 'openjdk version "25.0.4.1"', jarBytes: base.jarBytes },
        exists: true,
        javaVersion: { major: 25, line: 'openjdk version "25.0.4.1"' },
      },
    })
    expect(selfSlot.mode).toBe('none')
  })
})

describe('meta + release file reads', () => {
  let dir: string
  afterEach(() => {
    if (dir) rmSync(dir, { recursive: true, force: true })
  })

  it('reads JAVA_VERSION from a jlink release file', () => {
    dir = mkdtempSync(join(tmpdir(), 'aot-test-'))
    writeFileSync(join(dir, 'release'), 'JAVA_VERSION="25.0.4.1"\nJAVA_VERSION_DATE="2026-08-18"\n')
    expect(readJreVersion(dir)).toBe('25.0.4.1')
  })

  it('tolerates a missing release file and corrupt meta JSON', () => {
    dir = mkdtempSync(join(tmpdir(), 'aot-test-'))
    expect(readJreVersion(join(dir, 'nope'))).toBeNull()
    writeFileSync(join(dir, 'meta.json'), '{not json')
    expect(readMetaFile(join(dir, 'meta.json'))).toBeNull()
    writeFileSync(join(dir, 'meta2.json'), JSON.stringify({ kind: 'weird' }))
    expect(readMetaFile(join(dir, 'meta2.json'))).toBeNull()
  })
})

describe('prepareAotLaunch', () => {
  let root: string
  afterEach(() => {
    if (root) rmSync(root, { recursive: true, force: true })
  })

  it('stages a self cache under <runtimeRoot>/aot-cache and validates it next launch', () => {
    root = mkdtempSync(join(tmpdir(), 'aot-prep-'))
    const jar = join(root, 'FengYu.jar')
    writeFileSync(jar, Buffer.alloc(64))

    // First launch: nothing cached anywhere → none + trainable (probed identity comes
    // from the fake java below).
    const jreDir = join(root, 'jre')
    mkdirSync(jreDir)
    writeFileSync(join(jreDir, 'release'), 'JAVA_VERSION="25.0.4.1"\n')
    writeFileSync(join(jreDir, 'FengYu.aot'), Buffer.alloc(8))
    writeFileSync(
      join(jreDir, 'FengYu.aot.meta.json'),
      JSON.stringify({ kind: 'ci', appVersion: '4.1.0-alpha.1', jvmVersion: '25.0.4.1', jarBytes: 64 }),
    )
    const first = prepareAotLaunch({
      jarPath: jar,
      jreDir,
      javaBin: '/bundled/jre/bin/java',
      runtimeRootDir: root,
      appVersion: '4.1.0-alpha.1',
    })
    expect(first.plan.mode).toBe('use')

    // Jar replaced by an update: bundled identity drifts → plain launch + training.
    writeFileSync(jar, Buffer.alloc(128))
    const second = prepareAotLaunch({
      jarPath: jar,
      jreDir,
      javaBin: '/bundled/jre/bin/java',
      runtimeRootDir: root,
      appVersion: '4.1.0-alpha.1',
    })
    expect(second.plan).toEqual({ mode: 'none', canTrain: true })
    expect(second.training).not.toBeNull()
  })

  it('prunes a stale self-cache when the bundled CI cache wins (and only then)', () => {
    root = mkdtempSync(join(tmpdir(), 'aot-prep-'))
    const jar = join(root, 'FengYu.jar')
    writeFileSync(jar, Buffer.alloc(64))
    const jreDir = join(root, 'jre')
    mkdirSync(jreDir)
    writeFileSync(join(jreDir, 'release'), 'JAVA_VERSION="25.0.4.1"\n')
    writeFileSync(join(jreDir, 'FengYu.aot'), Buffer.alloc(8))
    writeFileSync(
      join(jreDir, 'FengYu.aot.meta.json'),
      JSON.stringify({ kind: 'ci', appVersion: '4.1.0-alpha.1', jvmVersion: '25.0.4.1', jarBytes: 64 }),
    )
    // A dead self-cache left by a previous version's fallback training.
    const selfDir = join(root, 'aot-cache')
    mkdirSync(selfDir, { recursive: true })
    writeFileSync(join(selfDir, 'FengYu.aot'), Buffer.alloc(8))

    const prepared = prepareAotLaunch({
      jarPath: jar,
      jreDir,
      javaBin: '/bundled/jre/bin/java',
      runtimeRootDir: root,
      appVersion: '4.1.0-alpha.1',
    })
    expect(prepared.plan.mode).toBe('use')
    expect(existsSync(selfDir)).toBe(false) // ~170 MB of dead weight reclaimed

    // use/self must NOT prune (it IS the self cache) — and mode none must leave it for
    // the retrain to overwrite.
    mkdirSync(selfDir, { recursive: true })
    writeFileSync(join(selfDir, 'FengYu.aot'), Buffer.alloc(8))
    const selfOnly = prepareAotLaunch({
      jarPath: jar,
      jreDir: undefined, // lite: bundled never consulted
      javaBin: '/fake/bin/java',
      runtimeRootDir: root,
      appVersion: '4.1.0-alpha.1',
    })
    expect(selfOnly.plan.mode).toBe('none')
    expect(existsSync(selfDir)).toBe(true)
  })
})

describe('trainAotCacheInBackground', () => {
  let root: string
  afterEach(() => {
    if (root) rmSync(root, { recursive: true, force: true })
  })

  function fakeTrainingContext(javaBin = '/fake/bin/java'): TrainingContext {
    return {
      javaBin,
      jarPath: '/fake/FengYu.jar',
      jarBytes: 64,
      appVersion: '4.1.0-alpha.1',
      javaVersion: { major: 25, line: 'openjdk version "25.0.4.1"' },
      runtimeRootDir: root,
    }
  }

  /**
   * A spawn fake answering BOTH trainer JVM invocations: first the org.h2.tools.Shell
   * seed (exit per seedStatus), then the training boot (emits the boot log, optionally
   * writes a real dump file at the -XX:AOTCacheOutput path, exits per trainExit).
   */
  function makeSpawnFake(opts: {
    bootLog: string
    dump: boolean | 'small'
    seedStatus?: number
    trainExit?: number
  }) {
    const calls: { args: string[] }[] = []
    let seedAnswered = false
    const spawnFake = ((_bin: string, args: string[]) => {
      calls.push({ args: [...args] })
      const proc = new EventEmitter() as unknown as ChildProcess
      Object.assign(proc, {
        pid: 4321,
        stdout: new EventEmitter(),
        stderr: new EventEmitter(),
        stdin: {
          end: vi.fn(),
          on: vi.fn(),
          once: vi.fn(),
        },
        kill: vi.fn(),
      })
      if (!seedAnswered) {
        seedAnswered = true
        setImmediate(() => proc.emit('exit', opts.seedStatus ?? 0, null))
        return proc
      }
      setImmediate(() => {
        proc.stdout?.emit('data', Buffer.from(`${opts.bootLog}\n`))
        if (opts.dump) {
          const dumpArg = args.find((a) => a.startsWith('-XX:AOTCacheOutput='))!
          const bytes = opts.dump === 'small' ? 1024 : MIN_CACHE_BYTES + 1024
          writeFileSync(dumpArg.slice('-XX:AOTCacheOutput='.length), Buffer.alloc(bytes))
        }
        proc.emit('exit', opts.trainExit ?? 0, null)
      })
      return proc
    }) as unknown as typeof realSpawn
    return { spawnFake, calls }
  }

  it('stages a valid self cache + meta after an APP-mode training boot', async () => {
    root = mkdtempSync(join(tmpdir(), 'aot-train-'))
    const { spawnFake, calls } = makeSpawnFake({ bootLog: APP_MODE_BOOT_LOG, dump: true })
    const handle = trainAotCacheInBackground({
      context: fakeTrainingContext(),
      pluginsDir: '/nonexistent/plugins',
      log: () => {},
      spawnImpl: spawnFake,
    })
    await expect(handle.done).resolves.toBe(true)

    const cacheDir = join(root, 'aot-cache')
    expect(statSync(join(cacheDir, 'FengYu.aot')).size).toBeGreaterThan(MIN_CACHE_BYTES)
    const meta = JSON.parse(readFileSync(join(cacheDir, 'FengYu.aot.meta.json'), 'utf8'))
    expect(meta).toEqual({
      kind: 'self',
      appVersion: '4.1.0-alpha.1',
      jvmVersion: 'openjdk version "25.0.4.1"',
      jarBytes: 64,
    })
    // Missing real plugins dir → the throwaway runtime's own (empty) plugins dir.
    // calls[0] is the H2 seed; the training launch is calls[1].
    const trainingArgs = calls[1].args
    const pluginsArg = trainingArgs.find((a) => a.startsWith('-Dfengyu.plugins.directory='))!
    expect(pluginsArg.endsWith(join('.fengyu', 'plugins'))).toBe(true)
    // The self-exit property must ride along — it is what makes the dump land.
    expect(trainingArgs).toContain('-Dfengyu.aot.training-exit=true')
    expect(trainingArgs).toContain('-cp')
    expect(trainingArgs[trainingArgs.indexOf('-cp') + 1]).toBe('/fake/FengYu.jar')
    // The trainer token is random, never the fixed literal the production spawn avoids.
    const tokenArg = trainingArgs.find((a) => a.startsWith('--token='))!
    expect(tokenArg).not.toBe('--token=aot-self-train')
  })

  it('refuses to stage a cache from a SETUP-mode boot (bad seed fallback)', async () => {
    root = mkdtempSync(join(tmpdir(), 'aot-train-'))
    const { spawnFake } = makeSpawnFake({
      // SETUP reaches its own ready line — the gate must not be fooled by readiness.
      bootLog: SETUP_MODE_BOOT_LOG,
      dump: true,
    })
    const handle = trainAotCacheInBackground({
      context: fakeTrainingContext(),
      pluginsDir: '/nonexistent/plugins',
      log: () => {},
      spawnImpl: spawnFake,
    })
    await expect(handle.done).resolves.toBe(false)
    expect(() => statSync(join(root, 'aot-cache', 'FengYu.aot'))).toThrow()
  })

  it('refuses to stage a truncated dump even from an APP-mode boot', async () => {
    root = mkdtempSync(join(tmpdir(), 'aot-train-'))
    const { spawnFake } = makeSpawnFake({ bootLog: APP_MODE_BOOT_LOG, dump: 'small' })
    const handle = trainAotCacheInBackground({
      context: fakeTrainingContext(),
      pluginsDir: '/nonexistent/plugins',
      log: () => {},
      spawnImpl: spawnFake,
    })
    await expect(handle.done).resolves.toBe(false)
    expect(() => statSync(join(root, 'aot-cache', 'FengYu.aot'))).toThrow()
  })

  it('refuses to stage when the training JVM exits non-zero', async () => {
    root = mkdtempSync(join(tmpdir(), 'aot-train-'))
    const { spawnFake } = makeSpawnFake({ bootLog: APP_MODE_BOOT_LOG, dump: true, trainExit: 1 })
    const handle = trainAotCacheInBackground({
      context: fakeTrainingContext(),
      pluginsDir: '/nonexistent/plugins',
      log: () => {},
      spawnImpl: spawnFake,
    })
    await expect(handle.done).resolves.toBe(false)
    expect(() => statSync(join(root, 'aot-cache', 'FengYu.aot'))).toThrow()
  })

  it('gives up for this launch when the H2 seed fails', async () => {
    root = mkdtempSync(join(tmpdir(), 'aot-train-'))
    const { spawnFake, calls } = makeSpawnFake({
      bootLog: APP_MODE_BOOT_LOG,
      dump: true,
      seedStatus: 1,
    })
    const handle = trainAotCacheInBackground({
      context: fakeTrainingContext(),
      pluginsDir: '/nonexistent/plugins',
      log: () => {},
      spawnImpl: spawnFake,
    })
    await expect(handle.done).resolves.toBe(false)
    expect(calls).toHaveLength(1) // seed ran, no training launch without a seeded DB
  })

  it('stop() SIGTERMs the running trainer child and aborts staging', async () => {
    root = mkdtempSync(join(tmpdir(), 'aot-train-'))
    const procs: Array<{ proc: unknown; kill: ReturnType<typeof vi.fn> }> = []
    const spawnFake = (() => {
      const proc = new EventEmitter() as unknown as ChildProcess
      const kill = vi.fn()
      Object.assign(proc, {
        pid: 99,
        stdout: new EventEmitter(),
        stderr: new EventEmitter(),
        stdin: { end: vi.fn(), on: vi.fn(), once: vi.fn() },
        kill,
      })
      procs.push({ proc, kill })
      setImmediate(() => proc.emit('exit', 0, null))
      return proc
    }) as unknown as typeof realSpawn
    const handle = trainAotCacheInBackground({
      context: fakeTrainingContext(),
      pluginsDir: '/nonexistent/plugins',
      log: () => {},
      spawnImpl: spawnFake,
    })
    handle.stop()
    expect(procs[0].kill).toHaveBeenCalledWith('SIGTERM')
    await handle.done // resolves (false) without throwing
  })
})
