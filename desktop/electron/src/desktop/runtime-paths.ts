import { resolve } from 'node:path'

/** Program working directory shared by the Electron shell and Java backend. */
export function runtimeRoot(): string {
  return resolve(process.cwd())
}
