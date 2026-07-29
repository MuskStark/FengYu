import { describe, expect, it } from 'vitest'
import { resolve } from 'node:path'
import { runtimeRoot } from '../src/desktop/runtime-paths'

const originalWorkingDirectory = process.cwd()

describe('runtimeRoot', () => {
  it('uses the hidden FengYu directory under the program working directory', () => {
    expect(runtimeRoot()).toBe(resolve(originalWorkingDirectory, '.fengyu'))
  })
})
