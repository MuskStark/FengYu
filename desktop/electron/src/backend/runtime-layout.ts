/**
 * Where the bundled runtime assets live.
 *
 * Packaged: under `process.resourcesPath` (electron-builder `extraResources`):
 *   <resources>/binaries/FengYu.jar, <resources>/plugins/, <resources>/jre/bin/java (with-JRE variant).
 * Dev: resolved from FENGYU_JAR / FENGYU_PLUGINS env (the backend runs externally on :24056).
 */
export interface RuntimeLayout {
  /** Absolute path to the shaded FengYu jar. */
  jar: string
  /** Absolute path to the official .fyp plugins directory. */
  plugins: string
  /** Absolute path to the bundled java binary (with-JRE variant only); undefined when relying on PATH. */
  jre?: string
}

import { posix, win32 } from 'node:path'

/**
 * Resolve the runtime layout.
 *
 * @param isPackaged `app.isPackaged` in prod; false in dev.
 * @param resourcesPath `process.resourcesPath` (packaged only).
 * @param env process.env (or a subset) — dev reads FENGYU_JAR / FENGYU_PLUGINS.
 */
export function resolveLayout(
  isPackaged: boolean,
  resourcesPath: string,
  env: Record<string, string | undefined>,
): RuntimeLayout {
  if (isPackaged) {
    // Use the path module for the target platform so the produced paths use the correct
    // separator regardless of the host running the code (e.g. a unit test faking win32
    // on a POSIX host). `node:path`'s default `join` is bound to the *host* OS at module
    // load and would mix separators when the target platform differs from the host.
    const path = process.platform === 'win32' ? win32 : posix
    const javaName = process.platform === 'win32' ? 'java.exe' : 'java'
    return {
      jar: path.join(resourcesPath, 'binaries', 'FengYu.jar'),
      plugins: path.join(resourcesPath, 'plugins'),
      jre: path.join(resourcesPath, 'jre', 'bin', javaName),
    }
  }
  const jar = env.FENGYU_JAR
  const plugins = env.FENGYU_PLUGINS ?? ''
  if (!jar) {
    throw new Error(
      'Dev mode requires FENGYU_JAR (path to the shaded jar). ' +
        'Run `mvn -pl FengYu -am package -DskipTests` and set FENGYU_JAR to the resulting jar, ' +
        'or start the backend externally on :24056 and omit the jar.',
    )
  }
  return { jar, plugins, jre: undefined }
}
