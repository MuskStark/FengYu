import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const source = (file: string) => fs.readFileSync(path.resolve('src', file), 'utf8')

describe('Offline Python UI composition', () => {
  it('boots through mountFengYuApp inside the official Infinia chrome', () => {
    const main = source('main.tsx')
    const app = source('App.tsx')
    expect(main).toContain('mountFengYuApp')
    expect(app).toContain('PluginShell')
    expect(app).toContain('PluginBar')
    expect(app).toContain('StatusBar')
    // The React port must never reintroduce the retired Vue/Vuetify/mdi stack.
    // (Literals are assembled so sibling source-scan tests never self-match.)
    const vueImport = ['from', `'vue'`].join(' ')
    for (const file of ['main.tsx', 'App.tsx']) {
      const text = source(file)
      expect(text).not.toContain(vueImport)
      expect(text).not.toContain('vuetify')
      expect(text).not.toContain(`@mdi${'/'}`)
    }
  })

  it('opens on the Project panel via the focus-bar view pills', () => {
    const app = source('App.tsx')
    expect(app).toContain("useState('project')")
    // Views switch inside the top PluginBar (no sidebar in the 3.1 redesign).
    expect(app).toContain('value: \'project\'')
    expect(app).toContain('value: \'deploy\'')
    expect(app).toContain('value: \'doctor\'')
  })

  it('owns the visible project picker inside the Project panel', () => {
    const app = source('App.tsx')
    const project = source('panels/ProjectPanel.tsx')
    // The picker lives in the panel and requests a read-write workspace grant so
    // worker-side saves/builds stay inside the host's writable roots.
    expect(app).not.toContain('workspaceDirectory')
    expect(project).toContain('files.workspaceDirectory')
    expect(app).toContain('onProject={setProject}')
    expect(project).toContain('onSelect={selectProject}')
  })

  it('passes complete FileRef objects to worker calls', () => {
    const project = source('panels/ProjectPanel.tsx')
    const deploy = source('panels/DeployPanel.tsx')
    expect(project).toContain('projectDir: project as unknown as string')
    expect(project).toContain('projectDir: currentProject as unknown as string')
    expect(deploy).toContain('zipPath: bundle as unknown as string')
    expect(`${project}\n${deploy}`).not.toContain('refPath(')
  })

  it('keeps the configure → build → verify workflow on the T4 build-bench layout', () => {
    const project = source('panels/ProjectPanel.tsx')
    expect(project).toContain("t('opb.step.config')")
    expect(project).toContain("t('opb.step.build')")
    expect(project).toContain("t('opb.step.verify')")
    // T4 build-bench pieces: glowing status card + meteors + stage timeline + console.
    expect(project).toContain('GlowingEffect')
    expect(project).toContain('Meteors')
    expect(project).toContain('Terminal')
    expect(project).toContain('opb-status-card')
    expect(project).toContain('opb-stages')
  })

  it('loads a selected project once instead of re-entering through an effect', () => {
    const project = source('panels/ProjectPanel.tsx')
    expect(project).toContain('await loadConfig(next)')
    // No effect re-runs the config load when the project prop changes.
    expect(project).not.toContain('}, [project])')
  })

  it('never silently bails on a null project in the save handler', () => {
    // The original saveConfig bug was a silent `if (!project) return` in save() —
    // no RPC, no toast, no file. The fix must surface a toast on the null branch.
    const project = source('panels/ProjectPanel.tsx')
    const saveStart = project.indexOf('async function save()')
    const saveBody = project.slice(saveStart, project.indexOf('}', saveStart + 200))
    expect(saveBody).toContain("toast(t('opb.project.empty'))")
  })
})
