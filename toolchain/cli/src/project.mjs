import fs from 'node:fs/promises'
import path from 'node:path'

import { detectManifestMode, codeFirstOutputPaths } from './manifest-source.mjs'

/**
 * Classify a plugin project root and resolve its build model.
 *
 * Toolchain 2 uses one conventional layout and intentionally has no command-array DSL:
 * `manifest.json` (or `manifest.base.json` for code-first), `ui-src/` (or prebuilt
 * `ui/`), and an optional conventional Java, Python, or Go worker under `worker/`.
 *
 * @param {string} root - project root
 * @returns {Promise<{ kind: 'standard', root: string, config: object, manifestMode: 'manifest-first'|'code-first', compiledManifestPath?: string }>}
 */
export async function detectProject(root) {
  const dir = path.resolve(root)
  if (await exists(path.join(dir, 'fengyu.plugin.json'))) {
    throw new Error('fengyu.plugin.json was removed in Toolchain 2; use the standard project layout')
  }
  const nestedUiSource = await exists(path.join(dir, 'ui-src', 'package.json'))
  const rootUiSource = await exists(path.join(dir, 'package.json'))
  const uiSourceRoot = nestedUiSource ? path.join(dir, 'ui-src') : rootUiSource ? dir : null
  const staticUi = await exists(path.join(dir, 'ui', 'index.html'))
  if (!uiSourceRoot && !staticUi) throw new Error('plugin must contain ui-src/package.json, package.json, or ui/index.html')

  const { mode, error } = await detectManifestMode(dir)
  if (mode === 'none') throw new Error(error)

  const manifestFile = mode === 'code-first' ? 'manifest.base.json' : 'manifest.json'
  const manifest = JSON.parse(await fs.readFile(path.join(dir, manifestFile), 'utf8'))
  const runtime = manifest.backend?.runtime ?? 'java'
  let worker = null
  if (manifest.backend) {
    if (runtime === 'java') {
      let workerRoot = null
      if (await exists(path.join(dir, 'worker', 'pom.xml'))) workerRoot = path.join(dir, 'worker')
      else if (await exists(path.join(dir, 'pom.xml'))) workerRoot = dir
      if (workerRoot) worker = { runtime, root: workerRoot, artifact: await existingWorkerArtifact(workerRoot) }
    } else if (runtime === 'python' && await exists(path.join(dir, 'worker', 'worker.py'))) {
      const workerRoot = path.join(dir, 'worker')
      worker = { runtime, root: workerRoot, artifact: path.join(workerRoot, 'worker.py') }
    } else if (runtime === 'go' && await exists(path.join(dir, 'worker', 'go.mod'))) {
      const workerRoot = path.join(dir, 'worker')
      worker = { runtime, root: workerRoot, artifact: path.join(workerRoot, 'target', process.platform === 'win32' ? 'worker.exe' : 'worker') }
    }
  }

  return {
    kind: 'standard',
    root: dir,
    manifestMode: mode,
    ...(mode === 'code-first' ? { compiledManifestPath: codeFirstOutputPaths(dir).manifest } : {}),
    config: {
      ui: {
        root: uiSourceRoot,
        output: nestedUiSource ? path.join(dir, 'ui-src', 'dist') : path.join(dir, 'ui'),
      },
      worker,
      package: { outputDirectory: 'dist' },
    },
  }
}

async function existingWorkerArtifact(workerRoot) {
  try {
    const target = path.join(workerRoot, 'target')
    const jars = (await fs.readdir(target))
      .filter(name => name.endsWith('-worker.jar') && !name.startsWith('original-'))
      .sort()
    return jars.length === 1 ? path.join(target, jars[0]) : null
  } catch {
    return null
  }
}

/** @returns {Promise<boolean>} true if the path is reachable. */
async function exists(file) {
  try {
    await fs.access(file)
    return true
  } catch {
    return false
  }
}
