import fs from 'node:fs/promises'
import fsSync from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { detectProject } from './project.mjs'
import { runCommand, resolveCommand, spawnSpec } from './commands.mjs'
import { validate, validateRuntimeTree, readManifest, validateProjectManifest } from './manifest.mjs'
import { writeZip } from './zip.mjs'
import { assembleStaging } from './staging.mjs'
import { inspectArchive } from './archive.mjs'
import { createHash } from 'node:crypto'

/**
 * Build a plugin package (.fyp).
 *
 * Lifecycle for a `declared` project (fengyu.plugin.json):
 *   ui.prepare → ui.install (if needed) → [ui.test, worker.test] → ui.build → worker.build
 *   → assemble staging → validate staging → atomically package
 *
 * For legacy `vue-vite` / `static` projects, the zero-config behavior is
 * preserved: run the frontend build (vue-vite) then route through staging so
 * the produced archive still excludes src/node_modules/.git/target.
 *
 * `build` runs UI and worker tests by default; `--skip-tests` skips tests only,
 * never type checking or packaging. Failures leave NO partial output: no `.fyp`,
 * no `.tmp-*`, and no staging directory.
 *
 * @param {string} root - project root
 * @param {{ out?: string, run?: Function, skipTests?: boolean, hooks?: { onValidate?: () => void, onPackage?: () => void } }} [options]
 * @returns {Promise<{ output: string, files: number }>}
 */
export async function buildPlugin(root, options = {}) {
  const dir = path.resolve(root)
  const { out, run = runCommand, skipTests = false, hooks = {} } = options

  const project = await detectProject(dir)

  if (project.kind === 'declared') {
    await runDeclaredLifecycle(project, run, skipTests)
  } else if (project.kind === 'vue-vite') {
    // Legacy zero-config Vue: run the frontend build first (emits ui/).
    await run('npm', ['run', 'build'], { cwd: dir })
  }

  const manifest = await readManifest(dir)
  const output = out ?? path.resolve(dir, project.config?.package?.outputDirectory ?? 'dist-package', `${manifest.id}-${manifest.version}.fyp`)

  return atomicPackage(project, output, hooks)
}

async function runDeclaredLifecycle(project, run, skipTests) {
  const cfg = project.config
  // ui.prepare (e.g. building shared tooling dependencies) runs first.
  if (cfg.ui) {
    for (const command of cfg.ui.prepare ?? []) {
      await runConfigured(command, cfg.ui.root, run)
    }
    // Install only when node_modules is absent or the lockfile fingerprint drifted.
    await ensureUiInstalled(cfg.ui, run)
    if (!skipTests) {
      await runConfigured(cfg.ui.test, cfg.ui.root, run)
      if (cfg.worker) await runConfigured(cfg.worker.test, cfg.worker.root, run)
    }
    await runConfigured(cfg.ui.build, cfg.ui.root, run)
  }
  if (cfg.worker) {
    await runConfigured(cfg.worker.build, cfg.worker.root, run)
  }
}

async function runConfigured(command, cwd, run) {
  const resolved = await resolveCommand(command, cwd)
  const spec = spawnSpec(resolved)
  await run(spec.command, spec.args, { cwd, env: resolved.env, shell: spec.shell })
}

async function ensureUiInstalled(ui, run) {
  const nodeModules = path.join(ui.root, 'node_modules')
  const lockfile = path.join(ui.root, 'package-lock.json')
  const hashFile = path.join(nodeModules, '.fengyu-lock-hash')
  const currentHash = await lockFingerprint(lockfile)
  const installedHash = await readFileSafe(hashFile)
  if (currentHash && installedHash === currentHash && fsSync.existsSync(nodeModules)) return
  // `npm ci` requires a committed lockfile; when none exists yet (e.g. a fresh
  // scaffold created with --no-install), fall back to `npm install` which also
  // generates the lockfile the next `ci` will pin to.
  const installCommand = (!currentHash && ui.install[0] === 'npm' && ui.install[1] === 'ci')
    ? ['npm', 'install', ...ui.install.slice(2)]
    : ui.install
  await runConfigured(installCommand, ui.root, run)
  const freshHash = await lockFingerprint(lockfile)
  if (freshHash) {
    await fs.mkdir(nodeModules, { recursive: true })
    await fs.writeFile(hashFile, freshHash)
  }
}

async function lockFingerprint(lockfile) {
  try {
    const data = await fs.readFile(lockfile)
    return createHash('sha256').update(data).digest('hex')
  } catch {
    return null
  }
}

async function readFileSafe(file) {
  try { return (await fs.readFile(file, 'utf8')).trim() } catch { return null }
}

async function atomicPackage(project, output, hooks) {
  const stagingParent = path.dirname(output)
  await fs.mkdir(stagingParent, { recursive: true })
  const staging = await fs.mkdtemp(path.join(stagingParent, '.staging-'))
  const tmp = `${output}.tmp-${process.pid}-${Math.random().toString(36).slice(2)}`
  try {
    await assembleStaging(project, staging)

    // Validate the staged runtime tree (and manifest).
    const errors = await validateRuntimeTree(project, staging)
    if (errors.length) throw new Error(errors.join('\n'))
    hooks.onValidate?.()

    // Write the archive to a temp sibling, validate it, then atomically rename.
    const result = await writeZip(staging, tmp)
    await inspectArchive(tmp)
    await fs.rename(tmp, output)
    // Emit a SHA256 sidecar so official-package installs can verify integrity. Format matches
    // GNU coreutils `sha256sum -c`: `<hex>  <basename>`. Opt-in verification lives in the host's
    // OfficialPluginSeeder; absence is tolerated for backwards compatibility / dev workflows.
    await writeSha256Sidecar(output)
    hooks.onPackage?.()
    return { output, files: result.files }
  } finally {
    await fs.rm(staging, { recursive: true, force: true }).catch(() => {})
    await fs.rm(tmp, { force: true }).catch(() => {})
  }
}

/**
 * Write `<archive>.sha256` next to a packaged `.fyp`. Format: `<hex>  <basename>`
 * (binary-mode marker omitted so a missing file is the only failure mode). The sidecar
 * is written to a temp file first and atomically renamed so a crash never leaves a
 * partial checksum line that would falsely "verify" a corrupt package.
 */
async function writeSha256Sidecar(output) {
  const sidecar = `${output}.sha256`
  const tmpSidecar = `${sidecar}.tmp-${process.pid}`
  try {
    const hash = await sha256OfFile(output)
    const line = `${hash}  ${path.basename(output)}\n`
    await fs.writeFile(tmpSidecar, line, 'utf8')
    await fs.rename(tmpSidecar, sidecar)
  } catch (e) {
    await fs.rm(tmpSidecar, { force: true }).catch(() => {})
    throw e
  }
}

async function sha256OfFile(file) {
  const { createReadStream } = await import('node:fs')
  const { pipeline } = await import('node:stream/promises')
  const stream = createReadStream(file)
  const hash = createHash('sha256')
  await pipeline(stream, hash)
  return hash.digest('hex')
}
