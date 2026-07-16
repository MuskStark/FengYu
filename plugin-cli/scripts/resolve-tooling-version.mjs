#!/usr/bin/env node
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export function resolveToolingVersion({ inputVersion = '', refName = '' }) {
  let value = inputVersion
  if (!value) {
    if (!refName.startsWith('plugin-tooling-v')) {
      throw new Error('release tag must start with plugin-tooling-v')
    }
    value = refName.slice('plugin-tooling-v'.length)
  }
  if (!isSemanticVersion(value)) {
    throw new Error(`tooling version is not semantic versioning: ${value}`)
  }
  return value
}

function isSemanticVersion(value) {
  const match = value.match(/^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/)
  if (!match) return false
  if (match.slice(1, 4).some((part) => part.length > 1 && part.startsWith('0'))) return false
  if (match[4]?.split('.').some((part) => /^\d+$/.test(part) && part.length > 1 && part.startsWith('0'))) return false
  return true
}

async function verifyRepositoryVersion(root, version) {
  const npmPackages = [
    'plugin-cli/package.json',
    'plugin-sdk/typescript/package.json',
    'plugin-ui/vue/package.json',
  ]
  const mismatches = []
  for (const relative of npmPackages) {
    const pkg = JSON.parse(await fs.readFile(path.join(root, relative), 'utf8'))
    if (pkg.version !== version) mismatches.push(`${relative}: ${pkg.version}`)
  }
  const pomFile = 'FengYu-Plugin-Sdk/pom.xml'
  const pom = await fs.readFile(path.join(root, pomFile), 'utf8')
  const pomVersion = pom.match(/<version>([^<]+)<\/version>/)?.[1]
  if (pomVersion !== version) mismatches.push(`${pomFile}: ${pomVersion ?? '<missing>'}`)
  if (mismatches.length) {
    throw new Error(`tooling version ${version} does not match:\n${mismatches.join('\n')}`)
  }
}

async function main(argv) {
  const options = {}
  for (let i = 0; i < argv.length; i += 2) {
    const name = argv[i]
    const value = argv[i + 1]
    if (value === undefined || !['--input', '--ref', '--github-output'].includes(name)) {
      throw new Error(`unknown or incomplete option: ${name}`)
    }
    options[name] = value
  }
  const version = resolveToolingVersion({ inputVersion: options['--input'], refName: options['--ref'] })
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
  await verifyRepositoryVersion(root, version)
  if (options['--github-output']) await fs.appendFile(options['--github-output'], `version=${version}\n`)
  else process.stdout.write(version + '\n')
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).catch((error) => {
    console.error(`Error: ${error.message}`)
    process.exitCode = 1
  })
}
