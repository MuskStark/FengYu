/**
 * Resolves the effective frontend version. Defaults to the package version, but
 * release CI overrides it via `FENGYU_RELEASE_VERSION` (a validated semver tag
 * such as "4.0.0-alpha.1"). Throws if the resolved value is not a recognized
 * stable or alpha/beta/rc version.
 */
export declare function resolveFrontendVersion(
  packageVersion: string,
  env?: Record<string, string | undefined>,
): string
