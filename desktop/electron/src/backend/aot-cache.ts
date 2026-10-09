/**
 * JVM startup-cache (CDS / Project Leyden AOT) management for the backend sidecar.
 *
 * Two cache sources, one decision per launch:
 *
 * 1. **Bundled (with-JRE variant)** — CI trains `FengYu.aot` with the exact shipped
 *    jlink runtime and stages it inside `<resources>/jre/` next to the JVM it belongs
 *    to (see desktop/electron/scripts/train-aot-cache.sh). Validating an AOT cache
 *    requires the SAME classpath STRING at training and use time, so this mode spawns
 *    with `cwd=<jar-dir>` and the RELATIVE `-cp FengYu.jar` — the form CI trained with,
 *    independent of where the app ends up installed.
 *
 * 2. **Self-trained (fallback for every variant)** — the desktop shell backgrounds a
 *    one-shot training launch ~40s after a successful boot: a throwaway seeded runtime
 *    root (the e2e-smoke recipe: fresh H2 file DB, APP mode, never the user's DB),
 *    `-Dfengyu.aot.training-exit=true` so the JVM exits on its own and the dump flag
 *    writes the cache. The cache lands in `<runtimeRoot>/aot-cache/` and is keyed to
 *    the resolved java binary's version line, so PATH-java lite installs and post-update
 *    JRE swaps self-heal by retraining. Training happens at idle, NOT at quit: the quit
 *    path force-kills the tree after 5s, while a dump write alone takes ~2–8s.
 *
 * JDK mapping: 24+ uses `-XX:AOTCacheOutput`/`-XX:AOTCache` (Leyden), 13–23 uses
 * `-XX:ArchiveClassesAtExit`/`-XX:SharedArchiveFile` (dynamic AppCDS). Older JVMs run
 * uncached and never train. Every fallback is silent-by-construction: a missing or
 * mismatched cache just boots the plain way (the JVM itself ignores unusable archives),
 * so a bad cache can never break startup — only fail to speed it up.
 */
import { spawn, spawnSync, type ChildProcess } from 'node:child_process'
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  renameSync,
  rmSync,
  statSync,
  unlinkSync,
  writeFileSync,
  copyFileSync,
} from 'node:fs'
import { dirname, join } from 'node:path'
import { genToken } from '../util/token'

/** Identity record staged next to a cache file; written by CI (kind 'ci') or the trainer ('self'). */
export interface AotCacheMeta {
  kind: 'ci' | 'self'
  appVersion: string
  /** Exact JVM identity: the `JAVA_VERSION` value from a jlink release file, or the full `java -version` first line. */
  jvmVersion: string
  jarBytes: number
}

/** Which dump/use flag pair a JVM major version supports; null = run uncached, never train. */
export interface AotFlagSet {
  useFlag: string
  dumpFlag: string
}

export function aotFlagsForVersion(major: number): AotFlagSet | null {
  if (major >= 24) return { useFlag: '-XX:AOTCache', dumpFlag: '-XX:AOTCacheOutput' }
  if (major >= 13) return { useFlag: '-XX:SharedArchiveFile', dumpFlag: '-XX:ArchiveClassesAtExit' }
  return null
}

/** Parsed `java -version` identity. `line` is the full first line (vendor + build). */
export interface JavaVersionInfo {
  major: number
  line: string
}

/**
 * Parse the first `version "…"` line of `java -version` output (JVMs print it to
 * stderr; callers pass both streams concatenated). Handles both modern (`25.0.4.1`)
 * and legacy (`1.8.0_392`) numbering. Null when nothing parseable came out — the
 * caller must then run uncached rather than guess flags (an unrecognized -XX flag
 * makes the JVM refuse to start at all).
 */
export function parseJavaVersionOutput(output: string): JavaVersionInfo | null {
  const match = output.match(/version "([^"]+)"/)
  if (!match) return null
  const raw = match[1]
  const legacy = raw.match(/^1\.(\d+)/)
  const major = legacy ? Number(legacy[1]) : Number(raw.split(/[._-]/)[0])
  if (!Number.isInteger(major) || major <= 0) return null
  const line = output.split(/\r?\n/).find((l) => l.includes(`version "${raw}"`)) ?? `version "${raw}"`
  return { major, line: line.trim() }
}

/** JVM args overlay for a cached launch; `cwd` is only set for the relative-classpath bundled mode. */
export interface AotJvmArgs {
  flags: string[]
  classpath: string
  cwd?: string
}

export type AotLaunchPlan =
  | ({ mode: 'use'; source: 'bundled' | 'self' } & AotJvmArgs)
  | { mode: 'none'; canTrain: boolean }

export interface LaunchPlanInput {
  appVersion: string
  jarPath: string
  jarBytes: number
  /** Absolute cache file path + meta staged inside the bundled jre dir (with-JRE variant). */
  bundled?: { cachePath: string; meta: AotCacheMeta | null; exists: boolean; jreVersion: string }
  /** Self-trained cache directory contents under <runtimeRoot>/aot-cache. */
  self?: { cachePath: string; meta: AotCacheMeta | null; exists: boolean; javaVersion: JavaVersionInfo | null }
}

/**
 * Pure decision: which cache (if any) this launch may use, and whether background
 * training is worthwhile. Prefer the bundled CI cache (exact JRE+jar identity), then
 * the self-trained one (exact PATH-java identity), else plain launch + train later.
 */
export function resolveLaunchPlan(input: LaunchPlanInput): AotLaunchPlan {
  const bundled = input.bundled
  const bundledFlags = bundled ? aotFlagsForVersion(parseJvmReleaseVersion(bundled.jreVersion)) : null
  if (
    bundled?.exists &&
    bundledFlags &&
    bundled.meta?.kind === 'ci' &&
    bundled.meta.appVersion === input.appVersion &&
    bundled.meta.jvmVersion === bundled.jreVersion &&
    bundled.meta.jarBytes === input.jarBytes
  ) {
    return {
      mode: 'use',
      source: 'bundled',
      // MUST mirror the CI training launch byte for byte: relative classpath, cwd = jar dir.
      flags: [`${bundledFlags.useFlag}=${bundled.cachePath}`],
      classpath: 'FengYu.jar',
      cwd: dirname(input.jarPath),
    }
  }
  const self = input.self
  const selfVersion = self?.javaVersion ?? null
  const flags = selfVersion ? aotFlagsForVersion(selfVersion.major) : null
  if (
    self?.exists &&
    flags &&
    selfVersion &&
    self.meta?.kind === 'self' &&
    self.meta.appVersion === input.appVersion &&
    self.meta.jvmVersion === selfVersion.line &&
    self.meta.jarBytes === input.jarBytes
  ) {
    return {
      mode: 'use',
      source: 'self',
      // Self-trained caches dump with the absolute classpath spawn.ts always uses.
      flags: [`${flags.useFlag}=${self.cachePath}`],
      classpath: input.jarPath,
    }
  }
  // No usable cache. Training is possible whenever the JVM we would train with (the
  // bundled JRE's version, or the probed PATH java for lite) supports a dump flag.
  const bundledInput = input.bundled
  const trainMajor = bundledInput
    ? parseJvmReleaseVersion(bundledInput.jreVersion)
    : (selfVersion?.major ?? 0)
  return { mode: 'none', canTrain: trainMajor > 0 && aotFlagsForVersion(trainMajor) !== null }
}

/** `"25.0.4.1"` → 25; handles the legacy `1.8.0_x` shape; 0 when unparseable. */
export function parseJvmReleaseVersion(version: string): number {
  const legacy = version.match(/^1\.(\d+)/)
  const major = legacy ? Number(legacy[1]) : Number(version.split(/[._-]/)[0])
  return Number.isInteger(major) && major > 0 ? major : 0
}

/** Read `JAVA_VERSION="…"` from a jlink runtime's release file; null when absent/unreadable. */
export function readJreVersion(jreDir: string): string | null {
  try {
    const release = readFileSync(join(jreDir, 'release'), 'utf8')
    const match = release.match(/^JAVA_VERSION="([^"]+)"/m)
    return match ? match[1] : null
  } catch {
    return null
  }
}

/** Safe JSON meta read; a corrupt meta just means "no cache", never a failed boot. */
export function readMetaFile(path: string): AotCacheMeta | null {
  try {
    const parsed = JSON.parse(readFileSync(path, 'utf8')) as AotCacheMeta
    if (
      (parsed.kind === 'ci' || parsed.kind === 'self') &&
      typeof parsed.appVersion === 'string' &&
      typeof parsed.jvmVersion === 'string' &&
      typeof parsed.jarBytes === 'number'
    ) {
      return parsed
    }
    return null
  } catch {
    return null
  }
}

/**
 * Probe the resolved java binary's version (lite variant: PATH java; also used by the
 * trainer to decide its dump flag). Synchronous on purpose — the answer is needed
 * before the backend can be spawned, and it costs a few hundred ms only on installs
 * without a bundled JRE.
 */
export function probeJavaVersion(javaBin: string): JavaVersionInfo | null {
  try {
    const res = spawnSync(javaBin, ['-version'], { timeout: 15_000, windowsHide: true })
    if (res.error) return null
    return parseJavaVersionOutput(`${res.stderr ?? ''}\n${res.stdout ?? ''}`)
  } catch {
    return null
  }
}

export interface PrepareAotOptions {
  jarPath: string
  /** Bundled jre dir (<resources>/jre) when present — with-JRE variant. */
  jreDir?: string
  /** Resolved java binary (bundled or PATH); used only for the lite version probe. */
  javaBin: string
  runtimeRootDir: string
  appVersion: string
}

export interface PreparedAot {
  plan: AotLaunchPlan
  /** Context the background trainer needs; null when training cannot apply. */
  training: TrainingContext | null
}

export interface TrainingContext {
  javaBin: string
  jarPath: string
  jarBytes: number
  appVersion: string
  javaVersion: JavaVersionInfo
  runtimeRootDir: string
}

export const SELF_CACHE_DIR_NAME = 'aot-cache'
export const CACHE_FILE_NAME = 'FengYu.aot'
export const META_FILE_NAME = 'FengYu.aot.meta.json'

/**
 * Read the cache state + decide this launch's plan. All I/O failures degrade to
 * "no cache" — the resulting launch is the status-quo startup, never a broken one.
 */
export function prepareAotLaunch(opts: PrepareAotOptions): PreparedAot {
  let jarBytes = 0
  try {
    jarBytes = statSync(opts.jarPath).size
  } catch {
    return { plan: { mode: 'none', canTrain: false }, training: null }
  }

  const selfDir = join(opts.runtimeRootDir, SELF_CACHE_DIR_NAME)
  const selfCachePath = join(selfDir, CACHE_FILE_NAME)
  const selfMetaPath = join(selfDir, META_FILE_NAME)

  // Lite variant probing doubles as the self-cache identity check AND the trainer's
  // flag selector — an unparsable version must leave both untouched. With a bundled
  // JRE the identity is its release-file version (never the PATH java, which plays no
  // part in that variant).
  const jreVersion = opts.jreDir ? readJreVersion(opts.jreDir) : null
  const javaVersion = opts.jreDir
    ? jreVersion
      ? { major: parseJvmReleaseVersion(jreVersion), line: jreVersion }
      : null
    : probeJavaVersion(opts.javaBin)

  const plan = resolveLaunchPlan({
    appVersion: opts.appVersion,
    jarPath: opts.jarPath,
    jarBytes,
    bundled:
      opts.jreDir && jreVersion
        ? {
            cachePath: join(opts.jreDir, CACHE_FILE_NAME),
            meta: readMetaFile(join(opts.jreDir, META_FILE_NAME)),
            exists: existsSync(join(opts.jreDir, CACHE_FILE_NAME)),
            jreVersion,
          }
        : undefined,
    self: {
      cachePath: selfCachePath,
      meta: readMetaFile(selfMetaPath),
      exists: existsSync(selfCachePath),
      javaVersion,
    },
  })

  let training: TrainingContext | null = null
  if (plan.mode === 'none' && plan.canTrain) {
    // canTrain guarantees one of the identities parsed.
    if (javaVersion && javaVersion.major > 0 && javaVersion.line) {
      training = {
        javaBin: opts.javaBin,
        jarPath: opts.jarPath,
        jarBytes,
        appVersion: opts.appVersion,
        javaVersion,
        runtimeRootDir: opts.runtimeRootDir,
      }
    }
  }
  if (plan.mode === 'use' && plan.source === 'bundled') {
    // The bundled cache won: any self-trained cache under the runtime root is now dead
    // weight (its JVM identity is this bundle's JRE, which the bundled path never
    // consults again). Prune it so a ~170 MB file cannot linger forever — e.g. left by
    // the self-training fallback of a previous version whose update restored the CI
    // cache. Best effort: a failure to delete only costs disk.
    try {
      rmSync(join(opts.runtimeRootDir, SELF_CACHE_DIR_NAME), { recursive: true, force: true })
    } catch {
      // read-only runtime root or a scanner holding files — ignore
    }
  }
  return { plan, training }
}

export interface TrainAotOptions {
  context: TrainingContext
  /** Real plugins dir, pointed at read-only for class-coverage parity with real boots. */
  pluginsDir: string
  log: (message: string) => void
  /** Injectable for tests. */
  spawnImpl?: typeof spawn
  /** How long the training launch may take end to end (boot + dump write). */
  timeoutMs?: number
}

export interface TrainingHandle {
  done: Promise<boolean>
  stop(): void
}

/** Minimal dump sanity bound, mirroring train-aot-cache.sh: a SETUP-context dump or a
 *  truncated file must never be staged as a valid cache. */
export const MIN_CACHE_BYTES = 20 * 1024 * 1024

/**
 * Seed the throwaway H2 database via org.h2.tools.Shell — ASYNC on purpose: a
 * synchronous JVM boot here (spawnSync) would block the whole Electron main process —
 * every window, IPC handler, and the tray — for the JVM's lifetime, exactly when the
 * user is mid-session (the trainer fires ~40s after boot).
 */
function seedH2Database(
  spawnImpl: typeof spawn,
  javaBin: string,
  jarPath: string,
  dbFile: string,
  timeoutMs: number,
  /** Registers the seed child so the handle's stop() can kill it too — a quit during
   *  the seed must not leave that JVM orphaned for its full boot. */
  onSpawned: (proc: ChildProcess) => void,
): Promise<boolean> {
  return new Promise((resolve) => {
    const proc = spawnImpl(
      javaBin,
      ['-cp', jarPath, 'org.h2.tools.Shell', '-url', `jdbc:h2:file:${dbFile}`, '-user', 'sa', '-password', ''],
      { stdio: ['pipe', 'ignore', 'ignore'], windowsHide: true },
    )
    onSpawned(proc)
    const timer = setTimeout(() => {
      proc.kill('SIGKILL')
      resolve(false)
    }, timeoutMs)
    timer.unref()
    proc.once('error', () => {
      clearTimeout(timer)
      resolve(false)
    })
    proc.once('exit', (code) => {
      clearTimeout(timer)
      resolve(code === 0)
    })
    // EPIPE when the shell exits before consuming stdin — the exit handler above is
    // the verdict; the write error itself must not crash the main process.
    proc.stdin?.once('error', () => {})
    proc.stdin?.end('SELECT 1;\n')
  })
}

/**
 * Run the one-shot background training launch (self-cache). Resolves true when a valid
 * cache + meta were staged under <runtimeRoot>/aot-cache. Never throws — a failed
 * training is a lost optimization, retried on the next app launch, nothing more.
 */
export function trainAotCacheInBackground(opts: TrainAotOptions): TrainingHandle {
  const { context } = opts
  const spawnImpl = opts.spawnImpl ?? spawn
  const timeoutMs = opts.timeoutMs ?? 420_000
  const flags = aotFlagsForVersion(context.javaVersion.major)

  let proc: ChildProcess | null = null
  let stopped = false

  const done = (async (): Promise<boolean> => {
    if (!flags) return false
    // Work dir under the runtime root, NOT the OS temp dir: the ~170 MB dump must land
    // on the same volume as the staged cache (a tmpfs temp would churn RAM for nothing
    // and a cross-volume copy would throw EXDEV), and the runtime root is writable by
    // definition at this point (the backend booted from it).
    mkdirSync(context.runtimeRootDir, { recursive: true })
    const work = mkdtempSync(join(context.runtimeRootDir, '.aot-train-'))
    try {
      const runtimeDir = join(work, '.fengyu')
      const dbFile = join(runtimeDir, 'database', 'fengyu')
      mkdirSync(join(runtimeDir, 'config'), { recursive: true })
      mkdirSync(dirname(dbFile), { recursive: true })
      writeFileSync(
        join(runtimeDir, 'config', 'datasource.properties'),
        [
          'db.type=h2',
          `db.url=jdbc:h2:file:${dbFile}`,
          'db.driver=org.h2.Driver',
          'db.dialect=org.hibernate.dialect.H2Dialect',
          'db.username=sa',
          'db.admin.username=sa',
          `db.file.path=${dbFile}`,
          '',
        ].join('\n'),
      )
      // Seed the empty H2 file the datasource above points at — without it the startup
      // probe finds no DB and boots the SETUP wizard context (train-aot-cache.sh: same
      // recipe, same reason).
      const seeded = await seedH2Database(
        spawnImpl,
        context.javaBin,
        context.jarPath,
        dbFile,
        120_000,
        // stop() must reach whichever child is live — seed now, training launch later.
        (child) => {
          proc = child
        },
      )
      if (!seeded || stopped) {
        opts.log('[aot-train] H2 seed failed; skipping self-training this launch')
        return false
      }

      const dumpPath = join(work, 'FengYu.aot')
      opts.log(`[aot-train] background training launch starting (JVM ${context.javaVersion.line})`)
      proc = spawnImpl(
        context.javaBin,
        [
          `-Dfengyu.runtime.dir=${runtimeDir}`,
          `-Dfengyu.plugins.directory=${existsSync(opts.pluginsDir) ? opts.pluginsDir : join(runtimeDir, 'plugins')}`,
          `-Dfengyu.plugins.data-directory=${join(runtimeDir, 'plugin-data')}`,
          '-Dfengyu.store.api-base=http://127.0.0.1:9',
          '-Dfengyu.aot.training-exit=true',
          `${flags.dumpFlag}=${dumpPath}`,
          '-cp',
          context.jarPath,
          'fan.summer.fengyu.HeadlessLauncher',
          '--port=0',
          // A throwaway loopback context on an OS-assigned port needs no real secret,
          // but a fixed known token on a command line is exactly what the production
          // spawn deliberately avoids — spend the same randomness here.
          `--token=${genToken()}`,
        ],
        {
          stdio: ['ignore', 'pipe', 'pipe'],
          windowsHide: true,
          // The trainer must be hermetic: strip the LIVE shell's browser-bridge
          // endpoint so this one-shot backend never attaches a second client to the
          // user's bridge session (empty value = the backend's bridge integration
          // degrades exactly as it does when the bridge is absent).
          env: { ...process.env, FENGYU_BROWSER_BRIDGE_PORT: '', FENGYU_BROWSER_BRIDGE_TOKEN: '' },
        },
      )
      // Boot-log capture: the mode gate needs proof the FULL app context started. The
      // Spring "Started <main class>" line cannot discriminate — both modes are launched
      // by HeadlessLauncher — so the gate looks for JPA/Flyway initialization, which the
      // SETUP context (no DataSource) can never log. Same markers as train-aot-cache.sh.
      let bootLog = ''
      proc.stdout?.on('data', (chunk: Buffer) => {
        bootLog += chunk.toString('utf8')
      })
      proc.stderr?.on('data', (chunk: Buffer) => {
        bootLog += chunk.toString('utf8')
      })

      const exited = await new Promise<number | null>((resolve) => {
        const child = proc
        if (!child) return resolve(null)
        const timer = setTimeout(() => {
          // Timeout or app quit: SIGTERM first (an orderly exit still writes the dump),
          // escalate after a grace window so a wedged trainer cannot linger.
          child.kill('SIGTERM')
          setTimeout(() => child.kill('SIGKILL'), 15_000).unref()
        }, timeoutMs)
        timer.unref()
        // A failed spawn (ENOENT and friends) emits error without exit — without this
        // handler the promise would hang for the full timeout.
        child.once('error', (err) => {
          clearTimeout(timer)
          resolve(null)
        })
        child.once('exit', (code) => {
          clearTimeout(timer)
          resolve(stopped ? null : code)
        })
      })
      // A quit that raced the training exit must also skip the staging block below —
      // teardownShell's stop() arrives after this process's exit event, and a multi-MB
      // synchronous copy inside the quitting main process is exactly what it prevents.
      if (exited === null || stopped) return false
      const appModeBoot =
        bootLog.includes('Initialized JPA EntityManagerFactory') ||
        /Successfully validated \d+ migrations/.test(bootLog)
      if (!appModeBoot) {
        // The gate matching INFO-level library lines is load-bearing: log enough of the
        // captured boot to diagnose a log-format/level drift instead of silently
        // retraining every launch.
        const tail = bootLog.slice(-2_000).replace(/\s+/g, ' ')
        opts.log(`[aot-train] training boot did not reach the APP context (exit ${exited}); boot tail: ${tail}`)
        return false
      }
      if (exited !== 0 || !existsSync(dumpPath) || statSync(dumpPath).size < MIN_CACHE_BYTES) {
        opts.log(`[aot-train] training launch did not produce a usable cache (exit ${exited}); will retry next launch`)
        return false
      }

      const selfDir = join(context.runtimeRootDir, SELF_CACHE_DIR_NAME)
      mkdirSync(selfDir, { recursive: true })
      const target = join(selfDir, CACHE_FILE_NAME)
      // Same-volume by construction (work dir lives under the runtime root) — rename,
      // with a copy fallback for exotic arrangements that defeat it.
      try {
        renameSync(dumpPath, target)
      } catch {
        copyFileSync(dumpPath, target)
        try {
          unlinkSync(dumpPath)
        } catch {
          // tmp cleanup below removes it anyway
        }
      }
      writeFileSync(
        join(selfDir, META_FILE_NAME),
        `${JSON.stringify(
          {
            kind: 'self',
            appVersion: context.appVersion,
            jvmVersion: context.javaVersion.line,
            jarBytes: context.jarBytes,
          },
          null,
          2,
        )}\n`,
      )
      opts.log('[aot-train] self-trained AOT cache staged; next launch uses it')
      return true
    } catch (err) {
      opts.log(`[aot-train] training failed: ${err instanceof Error ? err.message : String(err)}`)
      return false
    } finally {
      rmSync(work, { recursive: true, force: true })
    }
  })()

  return {
    done,
    stop() {
      stopped = true
      // Orderly first so an in-flight dump still lands; the exit watcher's escalation
      // covers a trainer that ignores SIGTERM.
      proc?.kill('SIGTERM')
    },
  }
}
