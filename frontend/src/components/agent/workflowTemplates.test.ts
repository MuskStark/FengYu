import { describe, expect, it } from 'vitest'
import { templateInputSchema, WORKFLOW_TEMPLATES } from './workflowTemplates'

describe('built-in workflow templates', () => {
  it('ships the excel split + batch email template', () => {
    const template = WORKFLOW_TEMPLATES.find((item) => item.id === 'excel-email')
    expect(template).toBeDefined()
  })

  it('wires edges only between declared nodes', () => {
    for (const template of WORKFLOW_TEMPLATES) {
      const ids = new Set(template.nodes.map((node) => node.id))
      for (const [source, target] of template.edges) {
        expect(ids.has(source)).toBe(true)
        expect(ids.has(target)).toBe(true)
      }
    }
  })

  it('names branch ports only on control-node edges', () => {
    const conditional = WORKFLOW_TEMPLATES.find((item) => item.id === 'conditional-tidy')!
    const branch = conditional.edges.find(([, , handle]) => handle !== undefined)
    expect(branch).toEqual(['check', 'tidy', 'true'])
    // The branch source must be the flow_if node — a handle on a plain edge compiles
    // into a runWhen condition that can never be satisfied.
    const check = conditional.nodes.find((node) => node.id === 'check')!
    expect(check.tool).toBe('flow_if')
    for (const template of WORKFLOW_TEMPLATES) {
      const tools = new Map(template.nodes.map((node) => [node.id, node.tool]))
      for (const [source, , handle] of template.edges) {
        if (handle === undefined) continue
        expect(tools.get(source)).toBe('flow_if')
      }
    }
  })

  it('ships the always-available host-tool templates', () => {
    const jsonTidy = WORKFLOW_TEMPLATES.find((item) => item.id === 'json-tidy')
    const conditional = WORKFLOW_TEMPLATES.find((item) => item.id === 'conditional-tidy')
    expect(jsonTidy?.requiredTools).toEqual(['json_format'])
    expect(conditional?.requiredTools).toEqual(['flow_if', 'json_format'])
    expect(WORKFLOW_TEMPLATES.find((item) => item.id === 'excel-split')?.requiredTools)
      .toEqual(['excel_complex_config', 'excel_execute'])
  })

  it('references only declared inputs in node args', () => {
    for (const template of WORKFLOW_TEMPLATES) {
      const declared = new Set(Object.keys(template.properties))
      const visit = (value: unknown): void => {
        if (Array.isArray(value)) return value.forEach(visit)
        if (value && typeof value === 'object') return Object.values(value).forEach(visit)
        if (typeof value !== 'string') return
        for (const match of value.matchAll(/{{inputs\.([A-Za-z0-9_-]+)}}/g)) {
          expect(declared.has(match[1])).toBe(true)
        }
      }
      for (const node of template.nodes) visit(node.args)
    }
  })

  it('references only declared nodes in result placeholders', () => {
    for (const template of WORKFLOW_TEMPLATES) {
      const ids = new Set(template.nodes.map((node) => node.id))
      for (const node of template.nodes) {
        for (const match of JSON.stringify(node.args).matchAll(/{{node\.([A-Za-z0-9_-]+)\.result/g)) {
          expect(ids.has(match[1])).toBe(true)
          expect(match[1]).not.toBe(node.id)
        }
      }
    }
  })

  it('materializes the input schema with picker and enum annotations', () => {
    const template = WORKFLOW_TEMPLATES.find((item) => item.id === 'excel-email')!
    const schema = templateInputSchema(template, (key) => `T(${key})`)

    expect(schema.type).toBe('object')
    expect(schema.properties?.workbook?.format).toBe('fengyu-file')
    expect(schema.properties?.workbook?.['x-fengyu-analyze']).toBe('excel')
    expect(schema.properties?.outputDir?.['x-fengyu-auto']).toBe('shared-directory')
    expect(schema.properties?.accountId?.['x-fengyu-enum']?.method).toBe('email_accounts_list')
    expect(schema.properties?.recipientTagIds?.['x-fengyu-enum']?.multiple).toBe(true)
    expect(schema.properties?.workbook?.title).toBe('T(agent.templates.excelEmail.workbook)')
    expect(schema.required).toContain('workbook')
  })

  it('ships multi-rule split configuration as an array-of-object input', () => {
    const template = WORKFLOW_TEMPLATES.find((item) => item.id === 'excel-email')!
    const schema = templateInputSchema(template, (key) => `T(${key})`)

    const rules = schema.properties?.rules as Record<string, any> | undefined
    expect(rules?.type).toBe('array')
    expect(schema.required).toContain('rules')
    // The split node binds the whole array: entries: {{inputs.rules}}
    const splitNode = template.nodes.find((node) => node.id === 'split')!
    expect(splitNode.args.entries).toBe('{{inputs.rules}}')
    // Row fields localize through titleKey and carry datalist option sources.
    const items = rules?.items as Record<string, any> | undefined
    expect(items?.type).toBe('object')
    const sheetField = items?.properties?.sheetName as Record<string, any> | undefined
    const columnField = items?.properties?.columnName as Record<string, any> | undefined
    expect(sheetField?.title).toBe('T(agent.templates.excelEmail.ruleSheet)')
    expect(sheetField?.['x-fengyu-options-from']).toBe('workbook-sheets')
    expect(columnField?.['x-fengyu-options-from']).toBe('workbook-columns')
  })

  it('wires the executable Excel output into email preparation and confirmation', () => {
    const template = WORKFLOW_TEMPLATES.find((item) => item.id === 'excel-email')!
    const write = template.nodes.find((node) => node.id === 'write')!
    const prepare = template.nodes.find((node) => node.id === 'prepare')!
    const send = template.nodes.find((node) => node.id === 'send')!

    expect(write.args.outputDir).toBe('{{inputs.outputDir}}')
    expect(prepare.args.inputDirectory).toBe('{{node.write.result.outputDir}}')
    expect(send.args.confirmationId)
      .toBe('{{node.prepare.result.confirmation.confirmationId}}')
    expect(send.requiresApproval).toBe(true)
    expect(template.edges).toEqual([
      ['split', 'write'],
      ['write', 'prepare'],
      ['prepare', 'send'],
    ])
  })
})
