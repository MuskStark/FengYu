import { randomBytes } from 'node:crypto'

/** Generate a cryptographically random 256-bit per-launch backend bearer token. */
export function genToken(): string {
  return `zf-${randomBytes(32).toString('hex')}`
}
