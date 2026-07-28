import { homedir } from 'node:os'
import { join } from 'node:path'

/** Stable writable root shared by the Electron shell and Java backend. */
export function runtimeRoot(): string {
  return join(homedir(), '.fengyu')
}

