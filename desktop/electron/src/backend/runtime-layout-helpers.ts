import { existsSync } from 'node:fs'
import type { RuntimeLayout } from './runtime-layout'

/**
 * Resolve the Java executable to run the backend with.
 * Order: bundled jre/bin/java (with-JRE variant) → PATH lookup (without-JRE).
 * Returns the absolute path, or 'java' as a PATH fallback (caller verifies via spawn error).
 */
export function resolveJava(layout: RuntimeLayout): string {
  if (layout.jre && existsSync(layout.jre)) {
    return layout.jre
  }
  // Defer to PATH; spawn() will surface ENOENT if java isn't installed.
  return 'java'
}
