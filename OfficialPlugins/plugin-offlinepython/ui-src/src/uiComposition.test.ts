import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const source = (file: string) => fs.readFileSync(path.resolve('src', file), 'utf8')

describe('Offline Python UI composition', () => {
  it('opens on the Project panel with an official icon rail', () => {
    const app = source('App.vue')
    expect(app).toContain("const active = ref('project')")
    // Icons ship as inline SVG path data from @mdi/js (the mdi webfont is not
    // reliably bundled into the iframe and renders tofu squares).
    expect(app).toContain('mdiHammerWrench')
    expect(app).toContain('mdiPackageVariantClosed')
    expect(app).toContain('mdiStethoscope')
    expect(app).not.toContain("'mdi-hammer-wrench'")
  })

  it('owns the visible project picker inside the Project panel', () => {
    const app = source('App.vue')
    const project = source('panels/ProjectPanel.vue')
    expect(app).not.toContain('FyDirectoryPicker')
    expect(project).toContain('FyDirectoryPicker')
    expect(project).toContain("(e: 'update:project', project: FileRef): void")
    expect(app).toContain('@update:project="project = $event"')
    expect(project).toContain('mode="workspace"')
  })

  it('passes complete FileRef objects to worker calls', () => {
    const project = source('panels/ProjectPanel.vue')
    const deploy = source('panels/DeployPanel.vue')
    expect(project).toContain('projectDir: props.project')
    expect(project).toContain('projectDir: project')
    expect(deploy).toContain('zipPath: bundle.value')
    expect(`${project}\n${deploy}`).not.toContain('refPath(')
  })

  it('merges configure + build + verify into the Project stepper', () => {
    const project = source('panels/ProjectPanel.vue')
    // Stepper drives the configure → build → verify flow.
    expect(project).toContain('v-stepper')
    expect(project).toContain("t('opb.step.config')")
    expect(project).toContain("t('opb.step.build')")
    expect(project).toContain("t('opb.step.verify')")
  })

  it('never silently bails on a null project in the save handler', () => {
    // The original saveConfig bug was a silent `if (!project) return` in save() —
    // no RPC, no toast, no file. The fix must surface a toast on the null branch.
    const project = source('panels/ProjectPanel.vue')
    const saveStart = project.indexOf('async function save()')
    const saveBody = project.slice(saveStart, project.indexOf('}', saveStart + 200))
    expect(saveBody).toContain("emit('toast', props.t('opb.project.empty'))")
  })
})
