import { spawn } from 'node:child_process'
import fs from 'node:fs/promises'
import fsSync from 'node:fs'
import path from 'node:path'

const GITHUB_PACKAGES_AUTH_MESSAGE =
  'GitHub Packages authentication is required. Set FENGYU_GITHUB_TOKEN or GITHUB_TOKEN with read:packages.'

/**
 * Run a child process and resolve on a zero exit, rejecting (preserving the
 * exit code) on any non-zero exit. Output streams through the parent stdio so
 * long-running commands like `npm install` give live feedback.
 *
 * @param {string} command - the executable to run (e.g. 'npm')
 * @param {string[]} args - arguments (e.g. ['install'])
 * @param {{ cwd?: string, stdio?: 'inherit' | 'pipe', shell?: boolean, env?: NodeJS.ProcessEnv }} [options]
 * @returns {Promise<{ code: number, command: string, args: string[] }>}
 */
export function runCommand(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd: options.cwd,
      env: options.env,
      stdio: options.stdio ?? 'inherit',
      shell: options.shell ?? process.platform === 'win32',
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

/**
 * Resolve a logical command into the exact executable + args the runner should
 * spawn. The only logical token is `maven`, which is replaced by the project's
 * Maven Wrapper (`mvnw` / `mvnw.cmd`) — there is NEVER a silent fallback to a
 * system-installed `mvn`. All other commands pass through verbatim.
 *
 * For Maven commands the returned `env` maps `GITHUB_TOKEN` → the child-only
 * `FENGYU_GITHUB_TOKEN` when the latter is absent, and defaults `GITHUB_ACTOR`,
 * so consumers can resolve the Java SDK from GitHub Packages without persisting
 * credentials. If the wrapper root carries a `settings.xml` that references
 * `maven.pkg.github.com` and neither token is present, this throws the precise
 * auth message. Repository-internal builds (no such settings) need no token.
 *
 * @param {string[]} command - command tokens, e.g. ['maven','test'] or ['npm','ci']
 * @param {string} cwd - working directory (where the wrapper is looked up, and upward)
 * @param {{ platform?: NodeJS.Platform }} [options]
 * @returns {Promise<{ command: string, args: string[], env: NodeJS.ProcessEnv, shell?: boolean }>}
 */
export async function resolveCommand(command, cwd, options = {}) {
  const platform = options.platform ?? process.platform
  if (command[0] !== 'maven') {
    return { command: command[0], args: command.slice(1), env: { ...process.env } }
  }
  const wrapper = await findMavenWrapper(path.resolve(cwd), platform)
  const env = { ...process.env }
  if (!env.FENGYU_GITHUB_TOKEN && env.GITHUB_TOKEN) env.FENGYU_GITHUB_TOKEN = env.GITHUB_TOKEN
  if (!env.GITHUB_ACTOR) env.GITHUB_ACTOR = 'fengyu-plugin-developer'
  await assertGithubPackagesAuth(wrapper.root, env)
  return { command: wrapper.command, args: command.slice(1), env }
}

/**
 * Turn a resolved command into the exact spawn arguments. Windows `.cmd` files
 * must be launched through `cmd.exe /d /s /c` explicitly rather than enabling
 * `shell: true` for arbitrary commands.
 * @returns {{ command: string, args: string[], shell: boolean }}
 */
export function spawnSpec(resolved) {
  if (resolved.command.endsWith('.cmd')) {
    return { command: 'cmd', args: ['/d', '/s', '/c', resolved.command, ...resolved.args], shell: false }
  }
  return { command: resolved.command, args: resolved.args, shell: false }
}

async function findMavenWrapper(startDir, platform) {
  const script = platform === 'win32' ? 'mvnw.cmd' : 'mvnw'
  let dir = startDir
  for (let depth = 0; depth < 16; depth++) {
    const candidate = path.join(dir, script)
    if (fsSync.existsSync(candidate)) {
      return { command: candidate, root: dir }
    }
    const parent = path.dirname(dir)
    if (parent === dir) break
    dir = parent
  }
  throw new Error(
    'Maven Wrapper (mvnw/mvnw.cmd) was not found. Run `mvn wrapper:wrapper -Dmaven=3.9.11` to generate it; ' +
    'the CLI never silently falls back to a system Maven.')
}

async function assertGithubPackagesAuth(wrapperRoot, env) {
  const settings = path.join(wrapperRoot, '.mvn', 'settings.xml')
  try {
    const text = await fs.readFile(settings, 'utf8')
    if (text.includes('maven.pkg.github.com') && !env.FENGYU_GITHUB_TOKEN && !env.GITHUB_TOKEN) {
      throw new Error(GITHUB_PACKAGES_AUTH_MESSAGE)
    }
  } catch (e) {
    if (e && e.message === GITHUB_PACKAGES_AUTH_MESSAGE) throw e
    /* no settings.xml → repository-internal build, no token required */
  }
}
