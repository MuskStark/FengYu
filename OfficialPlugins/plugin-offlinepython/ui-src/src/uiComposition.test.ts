import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const source = (file: string) => fs.readFileSync(path.resolve('src', file), 'utf8')

describe('Offline Python UI composition', () => {
  it('opens on Build & Verify with an official icon rail', () => {
    const app = source('App.vue')
    expect(app).toContain("const active = ref('build')")
    expect(app).toContain("icon: 'mdi-hammer-wrench'")
    expect(app).toContain("icon: 'mdi-tune-variant'")
    expect(app).toContain("icon: 'mdi-package-variant-closed'")
    expect(app).toContain("icon: 'mdi-stethoscope'")
  })

  it('owns the visible project picker inside Build & Verify', () => {
    const app = source('App.vue')
    const build = source('panels/BuildVerifyPanel.vue')
    expect(app).not.toContain('FyDirectoryPicker')
    expect(build).toContain('FyDirectoryPicker')
    expect(build).toContain("(e: 'update:project', project: FileRef): void")
    expect(app).toContain('@update:project="project = $event"')
  })
})
