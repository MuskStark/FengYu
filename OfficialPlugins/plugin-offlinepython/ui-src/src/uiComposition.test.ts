import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const source = (file: string) => fs.readFileSync(path.resolve('src', file), 'utf8')

describe('Offline Python UI composition', () => {
  it('opens on Build & Verify with an official icon rail', () => {
    const app = source('App.vue')
    expect(app).toContain("const active = ref('build')")
    // Icons ship as inline SVG path data from @mdi/js (the mdi webfont is not
    // reliably bundled into the iframe and renders tofu squares).
    expect(app).toContain('mdiHammerWrench')
    expect(app).toContain('mdiTuneVariant')
    expect(app).toContain('mdiPackageVariantClosed')
    expect(app).toContain('mdiStethoscope')
    expect(app).not.toContain("'mdi-hammer-wrench'")
  })

  it('owns the visible project picker inside Build & Verify', () => {
    const app = source('App.vue')
    const build = source('panels/BuildVerifyPanel.vue')
    expect(app).not.toContain('FyDirectoryPicker')
    expect(build).toContain('FyDirectoryPicker')
    expect(build).toContain("(e: 'update:project', project: FileRef): void")
    expect(app).toContain('@update:project="project = $event"')
    expect(build).toContain('mode="workspace"')
  })

  it('passes complete FileRef objects to worker calls', () => {
    const build = source('panels/BuildVerifyPanel.vue')
    const config = source('panels/ConfigPanel.vue')
    const deploy = source('panels/DeployPanel.vue')
    expect(build).toContain('projectDir: props.project')
    expect(config).toContain('projectDir: project')
    expect(deploy).toContain('zipPath: bundle.value')
    expect(`${build}\n${config}\n${deploy}`).not.toContain('refPath(')
  })
})
