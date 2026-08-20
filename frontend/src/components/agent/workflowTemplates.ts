import type { WorkflowEnumSource, WorkflowSchema } from './workflow'

/**
 * Built-in one-click workflow templates: a pre-wired node graph plus a run-form input
 * schema an ordinary (non-technical) user fills in — no tool knowledge required. Titles,
 * descriptions and goals are i18n keys resolved when the template is applied.
 */
export interface WorkflowTemplateProperty {
  type: string
  titleKey: string
  descriptionKey?: string
  default?: unknown
  /** `fengyu-file` / `fengyu-directory` — the run form renders a picker instead of free text. */
  format?: 'fengyu-file' | 'fengyu-directory'
  /** `shared-directory` — the run mints a host-managed cross-plugin scratch directory. */
  auto?: 'shared-directory'
  /** `excel` — analyze a picked workbook so sheet/column names become dropdown candidates. */
  analyze?: 'excel'
  enumSource?: WorkflowEnumSource
  /** Merged verbatim into the materialized JSON Schema (items, x-fengyu-* annotations, …). */
  extra?: Record<string, unknown>
}

export interface WorkflowTemplateNodeSpec {
  id: string
  tool: string
  descriptionKey: string
  args: Record<string, unknown>
  x: number
  y: number
  requiresApproval?: boolean
}

export interface WorkflowTemplate {
  id: string
  icon: string
  titleKey: string
  descriptionKey: string
  goalKey: string
  /** Tool names the canvas must have available (plugins installed + enabled). */
  requiredTools: string[]
  nodes: WorkflowTemplateNodeSpec[]
  /**
   * Wired edges as [sourceId, targetId] pairs; a third element names the SOURCE
   * branch port of a control node (flow_if: 'true' | 'false') — the compiled
   * runWhen condition.
   */
  edges: Array<readonly [string, string] | readonly [string, string, string]>
  properties: Record<string, WorkflowTemplateProperty>
  required: string[]
}

const excelEmail: WorkflowTemplate = {
  id: 'excel-email',
  icon: 'mdi-email-multiple-outline',
  titleKey: 'agent.templates.excelEmail.title',
  descriptionKey: 'agent.templates.excelEmail.description',
  goalKey: 'agent.templates.excelEmail.goal',
  requiredTools: ['excel_complex_config', 'excel_execute', 'email_send_batch', 'confirm_send'],
  nodes: [
    {
      id: 'split',
      tool: 'excel_complex_config',
      descriptionKey: 'agent.templates.excelEmail.splitStep',
      args: {
        action: 'add',
        filePath: '{{inputs.workbook}}',
        // One row per rule: each sheet is split by its own column (or copied whole when
        // the column is left blank). Bound as a whole array — the plugin defaults an
        // omitted headerIndex to the first header row.
        entries: '{{inputs.rules}}',
      },
      x: 60,
      y: 120,
    },
    {
      id: 'write',
      tool: 'excel_execute',
      descriptionKey: 'agent.templates.excelEmail.writeStep',
      args: { outputDir: '{{inputs.outputDir}}' },
      x: 400,
      y: 120,
    },
    {
      id: 'prepare',
      tool: 'email_send_batch',
      descriptionKey: 'agent.templates.excelEmail.prepareStep',
      args: {
        accountId: '{{inputs.accountId}}',
        recipientGroupTagIds: '{{inputs.recipientTagIds}}',
        // ccGroupTagIds is required by the tool but an authored `[]` reads as
        // "unconfigured" to the completeness check — route it through an optional
        // input seeded with [] so the node is complete and CC stays empty by default.
        ccGroupTagIds: '{{inputs.ccTagIds}}',
        // The attachment directory comes from where the split ACTUALLY wrote its
        // files (the excel node's outputDir output), not a second run-form input.
        inputDirectory: '{{node.write.result.outputDir}}',
        subject: '{{inputs.subject}}',
        plainText: '{{inputs.body}}',
      },
      x: 740,
      y: 120,
    },
    {
      id: 'send',
      tool: 'confirm_send',
      descriptionKey: 'agent.templates.excelEmail.sendStep',
      args: { confirmationId: '{{node.prepare.result.confirmation.confirmationId}}' },
      x: 1080,
      y: 120,
      requiresApproval: true,
    },
  ],
  edges: [
    ['split', 'write'],
    ['write', 'prepare'],
    ['prepare', 'send'],
  ],
  properties: {
    workbook: {
      type: 'string',
      format: 'fengyu-file',
      analyze: 'excel',
      titleKey: 'agent.templates.excelEmail.workbook',
      descriptionKey: 'agent.templates.excelEmail.workbookHint',
    },
    rules: {
      type: 'array',
      titleKey: 'agent.templates.excelEmail.rules',
      descriptionKey: 'agent.templates.excelEmail.rulesHint',
      extra: {
        items: {
          type: 'object',
          required: ['sheetName', 'columnName'],
          properties: {
            sheetName: {
              type: 'string',
              titleKey: 'agent.templates.excelEmail.ruleSheet',
              'x-fengyu-options-from': 'workbook-sheets',
            },
            columnName: {
              type: 'string',
              titleKey: 'agent.templates.excelEmail.ruleColumn',
              'x-fengyu-options-from': 'workbook-columns',
            },
          },
        },
      },
    },
    outputDir: {
      type: 'string',
      format: 'fengyu-directory',
      auto: 'shared-directory',
      titleKey: 'agent.templates.excelEmail.outputDir',
      descriptionKey: 'agent.templates.excelEmail.outputDirHint',
    },
    accountId: {
      type: 'integer',
      titleKey: 'agent.templates.excelEmail.account',
      enumSource: {
        plugin: 'fan.summer.email',
        method: 'email_accounts_list',
        items: 'accounts',
        value: 'id',
        label: 'email',
        labelSecondary: 'displayName',
      },
    },
    recipientTagIds: {
      type: 'array',
      titleKey: 'agent.templates.excelEmail.recipientTags',
      descriptionKey: 'agent.templates.excelEmail.recipientTagsHint',
      enumSource: {
        plugin: 'fan.summer.email',
        method: 'email_tags_list',
        items: 'tags',
        value: 'id',
        label: 'name',
        multiple: true,
      },
    },
    ccTagIds: {
      type: 'array',
      default: [],
      titleKey: 'agent.templates.excelEmail.ccTags',
    },
    subject: { type: 'string', titleKey: 'agent.templates.excelEmail.subject' },
    body: { type: 'string', titleKey: 'agent.templates.excelEmail.body' },
  },
  required: ['workbook', 'rules', 'accountId', 'recipientTagIds', 'subject'],
}

/** Excel-only split (no email leg): configure rules, execute, keep the file list. */
const excelSplit: WorkflowTemplate = {
  id: 'excel-split',
  icon: 'mdi-table-split-cell',
  titleKey: 'agent.templates.excelSplit.title',
  descriptionKey: 'agent.templates.excelSplit.description',
  goalKey: 'agent.templates.excelSplit.goal',
  requiredTools: ['excel_complex_config', 'excel_execute'],
  nodes: [
    {
      id: 'split',
      tool: 'excel_complex_config',
      descriptionKey: 'agent.templates.excelSplit.splitStep',
      args: {
        action: 'add',
        filePath: '{{inputs.workbook}}',
        entries: '{{inputs.rules}}',
      },
      x: 100,
      y: 160,
    },
    {
      id: 'write',
      tool: 'excel_execute',
      descriptionKey: 'agent.templates.excelSplit.writeStep',
      args: { outputDir: '{{inputs.outputDir}}' },
      x: 460,
      y: 160,
    },
  ],
  edges: [
    ['split', 'write'],
  ],
  properties: {
    workbook: {
      type: 'string',
      format: 'fengyu-file',
      analyze: 'excel',
      titleKey: 'agent.templates.excelSplit.workbook',
      descriptionKey: 'agent.templates.excelSplit.workbookHint',
    },
    rules: {
      type: 'array',
      titleKey: 'agent.templates.excelEmail.rules',
      descriptionKey: 'agent.templates.excelEmail.rulesHint',
      extra: {
        items: {
          type: 'object',
          required: ['sheetName', 'columnName'],
          properties: {
            sheetName: {
              type: 'string',
              titleKey: 'agent.templates.excelEmail.ruleSheet',
              'x-fengyu-options-from': 'workbook-sheets',
            },
            columnName: {
              type: 'string',
              titleKey: 'agent.templates.excelEmail.ruleColumn',
              'x-fengyu-options-from': 'workbook-columns',
            },
          },
        },
      },
    },
    outputDir: {
      type: 'string',
      format: 'fengyu-directory',
      auto: 'shared-directory',
      titleKey: 'agent.templates.excelEmail.outputDir',
      descriptionKey: 'agent.templates.excelEmail.outputDirHint',
    },
  },
  required: ['workbook', 'rules', 'outputDir'],
}

/** Always-available single-node template: tidy a pasted JSON with the host tool. */
const jsonTidy: WorkflowTemplate = {
  id: 'json-tidy',
  icon: 'mdi-code-json',
  titleKey: 'agent.templates.jsonTidy.title',
  descriptionKey: 'agent.templates.jsonTidy.description',
  goalKey: 'agent.templates.jsonTidy.goal',
  requiredTools: ['json_format'],
  nodes: [
    {
      id: 'tidy',
      tool: 'json_format',
      descriptionKey: 'agent.templates.jsonTidy.tidyStep',
      args: { json: '{{inputs.jsonText}}' },
      x: 200,
      y: 160,
    },
  ],
  edges: [],
  properties: {
    jsonText: {
      type: 'string',
      titleKey: 'agent.templates.jsonTidy.jsonText',
      descriptionKey: 'agent.templates.jsonTidy.jsonTextHint',
      extra: { 'x-fengyu-multiline': true },
    },
  },
  required: ['jsonText'],
}

/**
 * Branch demo (host tools only, always available): format the JSON only when the
 * marker input is filled — the false branch is intentionally empty so a run shows
 * the skip badge on the formatter when the condition fails.
 */
const conditionalTidy: WorkflowTemplate = {
  id: 'conditional-tidy',
  icon: 'mdi-source-branch',
  titleKey: 'agent.templates.conditionalTidy.title',
  descriptionKey: 'agent.templates.conditionalTidy.description',
  goalKey: 'agent.templates.conditionalTidy.goal',
  requiredTools: ['flow_if', 'json_format'],
  nodes: [
    {
      id: 'check',
      tool: 'flow_if',
      descriptionKey: 'agent.templates.conditionalTidy.checkStep',
      args: { left: '{{inputs.marker}}', operator: 'is_not_empty' },
      x: 100,
      y: 160,
    },
    {
      id: 'tidy',
      tool: 'json_format',
      descriptionKey: 'agent.templates.conditionalTidy.tidyStep',
      args: { json: '{{inputs.jsonText}}' },
      x: 460,
      y: 160,
    },
  ],
  edges: [
    ['check', 'tidy', 'true'],
  ],
  properties: {
    marker: {
      type: 'string',
      titleKey: 'agent.templates.conditionalTidy.marker',
      descriptionKey: 'agent.templates.conditionalTidy.markerHint',
    },
    jsonText: {
      type: 'string',
      titleKey: 'agent.templates.jsonTidy.jsonText',
      descriptionKey: 'agent.templates.jsonTidy.jsonTextHint',
      extra: { 'x-fengyu-multiline': true },
    },
  },
  required: ['jsonText'],
}

export const WORKFLOW_TEMPLATES: WorkflowTemplate[] = [excelEmail, excelSplit, jsonTidy, conditionalTidy]

/** Materializes a template's input annotations into the JSON schema the run form renders. */
export function templateInputSchema(
  template: WorkflowTemplate,
  translate: (key: string) => string,
): WorkflowSchema {
  const properties: Record<string, Record<string, unknown>> = {}
  for (const [name, property] of Object.entries(template.properties)) {
    const schema: Record<string, unknown> = {
      type: property.type,
      title: translate(property.titleKey),
    }
    if (property.descriptionKey) schema.description = translate(property.descriptionKey)
    if (property.default !== undefined) schema.default = property.default
    if (property.format) schema.format = property.format
    if (property.auto) schema['x-fengyu-auto'] = property.auto
    if (property.analyze) schema['x-fengyu-analyze'] = property.analyze
    if (property.enumSource) schema['x-fengyu-enum'] = property.enumSource
    if (property.extra) {
      // Nested titleKey entries (e.g. row-editor field labels) resolve through the
      // same translator before the schema reaches the run form.
      const localized = localizeTitleKeys(property.extra, translate)
      Object.assign(schema, localized)
    }
    properties[name] = schema
  }
  return { type: 'object', properties, required: [...template.required] }
}

function localizeTitleKeys(
  value: Record<string, unknown>,
  translate: (key: string) => string,
): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const [key, entry] of Object.entries(value)) {
    if (key === 'titleKey' && typeof entry === 'string') {
      out.title = translate(entry)
      continue
    }
    if (entry && typeof entry === 'object' && !Array.isArray(entry)) {
      out[key] = localizeTitleKeys(entry as Record<string, unknown>, translate)
    } else {
      out[key] = entry
    }
  }
  return out
}
