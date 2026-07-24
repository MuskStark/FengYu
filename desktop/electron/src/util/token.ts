/**
 * Per-launch auth token. Format: `zf-{hex(nanos)}-{hex(pid)}`.
 *
 * Mirrors the Rust `gen_token()` (nanos + pid, both hex-encoded) so backend token
 * validation is unchanged. No uuid dependency.
 *
 * NOTE: the nanos component uses `process.hrtime.bigint()` (a high-resolution monotonic
 * clock) instead of `Date.now()`-derived nanos. Node's `Date.now()` is only millisecond
 * resolution, so two rapid successive calls in the same ms would yield identical tokens
 * (same ms × 1e6, same pid). `hrtime.bigint()` gives true nanosecond resolution, matching
 * the Rust source's `SystemTime::now().as_nanos()` behavior and making each call unique.
 */
export function genToken(): string {
  const nanos = process.hrtime.bigint()
  const pid = BigInt(process.pid)
  return `zf-${nanos.toString(16)}-${pid.toString(16)}`
}
