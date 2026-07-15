import { spawn } from 'node:child_process'

/**
 * Run a child process and resolve on a zero exit, rejecting (preserving the
 * exit code) on any non-zero exit. Output streams through the parent stdio so
 * long-running commands like `npm install` give live feedback.
 *
 * @param {string} command - the executable to run (e.g. 'npm')
 * @param {string[]} args - arguments (e.g. ['install'])
 * @param {{ cwd?: string, stdio?: 'inherit' | 'pipe' }} [options]
 * @returns {Promise<{ code: number, command: string, args: string[] }>}
 */
export function runCommand(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd: options.cwd,
      stdio: options.stdio ?? 'inherit',
      shell: process.platform === 'win32',
    })
    child.on('error', (err) => reject(err))
    child.on('exit', (code, signal) => {
      if (signal) {
        const err = new Error(`${command} ${args.join(' ')} terminated by ${signal}`)
        err.code = 1
        err.signal = signal
        reject(err)
      } else if (code === 0) {
        resolve({ code: 0, command, args })
      } else {
        const err = new Error(`${command} ${args.join(' ')} exited with code ${code}`)
        err.code = code
        reject(err)
      }
    })
  })
}
