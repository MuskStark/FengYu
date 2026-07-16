import fs from 'node:fs/promises'
import path from 'node:path'
import { detectProject } from './project.mjs'
import { runCommand } from './commands.mjs'
import { validate, readManifest } from './manifest.mjs'
import { writeZip } from './zip.mjs'

/**
 * Build a plugin package: optionally run the frontend build, validate the
 * manifest, then atomically produce the `.fyp` archive.
 *
 * Pipeline (depends on the detected project kind):
 *  - `vue-vite`: run `npm run build` first (it emits `ui/`), THEN validate the
 *    manifest + `ui.entry`, THEN package. A frontend-build failure is rethrown
 *    as-is and NO `.fyp` is produced.
 *  - `static`: skip npm and go straight to validate + package (legacy path).
 *
 * The archive write is atomic: the zip is written to a temporary sibling
 * `<output>.tmp-<pid>` and renamed onto the final path only after `writeZip`
 * succeeds. The temp file is removed in `finally` so a failure (build error,
 * validation error, or rename failure) never leaves a partial `.fyp` or a
 * stale `.tmp-*` behind.
 *
 * @param {string} root - project root
 * @param {{ out?: string, run?: (cmd: string, args: string[], opts?: object) => Promise<unknown>, hooks?: { onValidate?: () => void, onPackage?: () => void } }} [options]
 * @returns {Promise<{ output: string, files: number }>}
 */
export async function buildPlugin(root, options = {}) {
  const dir = path.resolve(root)
  const { out, run = runCommand, hooks = {} } = options

  const project = await detectProject(dir)
  const kind = project.kind

  if (kind === 'vue-vite') {
    // Run the frontend build FIRST. If it rejects, let the rejection propagate
    // untouched (the message + exit code are preserved) so callers see the real
    // cause; no `.fyp` is ever written.
    await run('npm', ['run', 'build'], { cwd: dir })
  }

  // Validate AFTER the frontend build so ui.entry (produced by `npm run build`)
  // is present. Throw the joined errors like the `validate` command does.
  const errors = await validate(dir)
  if (errors.length) throw new Error(errors.join('\n'))
  hooks.onValidate?.()

  // Resolve the output path from the manifest's id/version when not supplied.
  const manifest = await readManifest(dir)
  const output = out ?? path.resolve(dir, 'dist-package', `${manifest.id}-${manifest.version}.fyp`)

  // Package atomically: write to a temp sibling, then rename on success.
  const tmp = `${output}.tmp-${process.pid}`
  let result
  try {
    result = await writeZip(dir, tmp)
    await fs.rename(tmp, output)
  } finally {
    // Clean any leftover temp file whether the write/rename succeeded or not.
    await fs.rm(tmp, { force: true }).catch(() => {})
  }

  hooks.onPackage?.()
  return { output, files: result.files }
}
