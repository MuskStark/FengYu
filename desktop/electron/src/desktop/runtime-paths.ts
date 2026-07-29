import { resolve } from 'node:path'

/** Writable runtime root under the program working directory. */
export function runtimeRoot(): string {
  return resolve(process.cwd(), '.fengyu')
}
