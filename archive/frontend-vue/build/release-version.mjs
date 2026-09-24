const VERSION = /^\d+\.\d+\.\d+(?:-(?:alpha|beta|rc)\.\d+)?$/
export function resolveFrontendVersion(packageVersion, env = process.env) {
  const value = env.FENGYU_RELEASE_VERSION || packageVersion
  if (!VERSION.test(value)) throw new Error(`Invalid frontend release version: ${value}`)
  return value
}
