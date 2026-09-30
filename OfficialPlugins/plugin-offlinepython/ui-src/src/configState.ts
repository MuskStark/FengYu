/**
 * Mirror of the worker-side `BuildConfig` (see
 * `domain/BuildConfig.java`). The UI keeps the FULL shape here so a save
 * round-trips every section — the worker deserializes the incoming object into
 * a fresh `BuildConfig`, so omitting a section would reset it to Java defaults
 * (data loss). See `OfflinePythonRpcHandlers.configSave`.
 *
 * `WorkerConfig` is the wire shape; `ConfigForm` is the flattened UI shape.
 */
export interface WorkerConfig {
  python?: {
    version?: string
    platforms?: string[]
    depPlatforms?: Record<string, string[]>
    implementation?: string
    installer?: boolean
    executable?: string
  }
  repository?: { output?: string; wheelDir?: string; cache?: boolean }
  download?: { mirror?: string; upgradePip?: boolean; recursive?: boolean; onlyBinary?: boolean }
  pkg?: { zip?: boolean; sha256?: boolean; readme?: boolean }
  bundle?: { autoPackage?: boolean; name?: string; sha256?: boolean }
}

/** Flattened form shape rendered by the config step. */
export interface ConfigForm {
  // python
  pythonVersion: string
  platformsCsv: string
  implementation: string
  installer: boolean
  executable: string
  // repository
  output: string
  wheelDir: string
  cache: boolean
  // download
  mirror: string
  upgradePip: boolean
  recursive: boolean
  onlyBinary: boolean
  // pkg
  zip: boolean
  pkgSha256: boolean
  readme: boolean
  // bundle
  autoPackage: boolean
  bundleName: string
  bundleSha256: boolean
}

/** Defaults — kept in lock-step with `BuildConfig.defaults()` on the worker. */
export const DEFAULT_FORM: ConfigForm = {
  pythonVersion: '3.12.10',
  platformsCsv: 'win_amd64',
  implementation: 'cp',
  installer: true,
  executable: '',
  output: 'output',
  wheelDir: 'wheelhouse',
  cache: true,
  mirror: 'official',
  upgradePip: true,
  recursive: true,
  onlyBinary: true,
  zip: true,
  pkgSha256: true,
  readme: true,
  autoPackage: false,
  bundleName: '',
  bundleSha256: true,
}

/** Normalize a worker config into the UI form, defaulting any omitted field. */
export function configForm(config?: WorkerConfig): ConfigForm {
  const p = config?.python
  const r = config?.repository
  const d = config?.download
  const k = config?.pkg
  const b = config?.bundle
  return {
    pythonVersion: p?.version ?? DEFAULT_FORM.pythonVersion,
    platformsCsv: p?.platforms?.join(', ') || DEFAULT_FORM.platformsCsv,
    implementation: p?.implementation ?? DEFAULT_FORM.implementation,
    installer: p?.installer ?? DEFAULT_FORM.installer,
    executable: p?.executable ?? DEFAULT_FORM.executable,
    output: r?.output ?? DEFAULT_FORM.output,
    wheelDir: r?.wheelDir ?? DEFAULT_FORM.wheelDir,
    cache: r?.cache ?? DEFAULT_FORM.cache,
    mirror: d?.mirror ?? DEFAULT_FORM.mirror,
    upgradePip: d?.upgradePip ?? DEFAULT_FORM.upgradePip,
    recursive: d?.recursive ?? DEFAULT_FORM.recursive,
    onlyBinary: d?.onlyBinary ?? DEFAULT_FORM.onlyBinary,
    zip: k?.zip ?? DEFAULT_FORM.zip,
    pkgSha256: k?.sha256 ?? DEFAULT_FORM.pkgSha256,
    readme: k?.readme ?? DEFAULT_FORM.readme,
    autoPackage: b?.autoPackage ?? DEFAULT_FORM.autoPackage,
    bundleName: b?.name ?? DEFAULT_FORM.bundleName,
    bundleSha256: b?.sha256 ?? DEFAULT_FORM.bundleSha256,
  }
}

/**
 * Rebuild the FULL worker config from the form. Every section is emitted so the
 * worker's `Gson.fromJson(..., BuildConfig.class)` does not reset omitted
 * sections to Java defaults. `depPlatforms` is preserved verbatim from the
 * incoming config if present (the UI does not edit it yet).
 */
export function buildWorkerConfig(form: ConfigForm, existing?: WorkerConfig): WorkerConfig {
  return {
    python: {
      version: form.pythonVersion,
      platforms: form.platformsCsv.split(',').map((s) => s.trim()).filter(Boolean),
      depPlatforms: existing?.python?.depPlatforms ?? {},
      implementation: form.implementation,
      installer: form.installer,
      executable: form.executable || undefined,
    },
    repository: { output: form.output, wheelDir: form.wheelDir, cache: form.cache },
    download: {
      mirror: form.mirror,
      upgradePip: form.upgradePip,
      recursive: form.recursive,
      onlyBinary: form.onlyBinary,
    },
    pkg: { zip: form.zip, sha256: form.pkgSha256, readme: form.readme },
    bundle: { autoPackage: form.autoPackage, name: form.bundleName, sha256: form.bundleSha256 },
  }
}
