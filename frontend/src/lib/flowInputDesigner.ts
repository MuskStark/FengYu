import { humanizeWorkflowField } from '@/lib/flowDisplay'
import { parseJsonObject } from '@/lib/flowGraph'

/**
 * Start-node run-input designer model (React port of the Vue FlowStartInspector
 * round-trip): edits the same JSON Schema the raw JSON textarea exposes — every
 * change round-trips through the schema text and the persisted format is
 * unchanged. Designer-owned facets (type/format/enum/examples/default) are
 * cleared before re-applying so a type switch cannot leave them stale, while
 * annotations the designer does not model survive an unrelated edit.
 */

export type DesignerType =
  | 'string' | 'textarea' | 'number' | 'boolean' | 'select' | 'file' | 'directory' | 'array' | 'object'

export const DESIGNER_TYPES: DesignerType[] = [
  'string', 'textarea', 'number', 'boolean', 'select', 'file', 'directory', 'array', 'object',
]

export const DESIGNER_TYPE_KEYS: Record<DesignerType, string> = {
  string: 'agent.inputTypeString',
  textarea: 'agent.inputTypeTextarea',
  number: 'agent.inputTypeNumber',
  boolean: 'agent.inputTypeBoolean',
  select: 'agent.inputTypeSelect',
  file: 'agent.inputTypeFile',
  directory: 'agent.inputTypeDirectory',
  array: 'agent.inputTypeArray',
  object: 'agent.inputTypeObject',
}

export interface DesignerField {
  name: string
  /** Name the field was parsed from — anchors preserved annotations on rename. */
  originalName: string
  title: string
  designerType: DesignerType
  fileAccess: 'read' | 'read-write'
  required: boolean
  options: string[]
  example: string
  helpText: string
  hasDefault: boolean
  defaultText: string
}

interface DesignerSchemaProperty {
  type?: string
  title?: string
  description?: string
  format?: string
  enum?: unknown[]
  examples?: unknown[]
  default?: unknown
  'x-fengyu-file-access'?: 'read' | 'read-write'
  'x-fengyu-multiline'?: boolean
  [key: string]: unknown
}

interface DesignerSchema {
  type?: string
  properties?: Record<string, DesignerSchemaProperty>
  required?: string[]
  [key: string]: unknown
}

function parseSchema(schemaText: string): DesignerSchema {
  return parseJsonObject(schemaText) ?? {}
}

function designerTypeOf(property: DesignerSchemaProperty): DesignerType {
  if (property.format === 'fengyu-directory') return 'directory'
  if (property.format === 'fengyu-file') return 'file'
  if (property.type === 'number' || property.type === 'integer') return 'number'
  if (property.type === 'boolean') return 'boolean'
  if (property.type === 'array') return 'array'
  if (property.type === 'object') return 'object'
  if (property.enum?.length) return 'select'
  if (property['x-fengyu-multiline']) return 'textarea'
  return 'string'
}

function formatDefaultValue(value: unknown, type: DesignerType): string {
  if (value === undefined) return ''
  if (type === 'array' || type === 'object') {
    try {
      return JSON.stringify(value)
    } catch {
      return ''
    }
  }
  return String(value)
}

/** Parses the schema text into designer rows (unknown shapes degrade to string). */
export function parseDesignerFields(schemaText: string): DesignerField[] {
  const schema = parseSchema(schemaText)
  const required = new Set(Array.isArray(schema.required) ? schema.required as string[] : [])
  return Object.entries(schema.properties ?? {}).map(([name, property]) => {
    const designerType = designerTypeOf(property)
    return {
      name,
      originalName: name,
      title: typeof property.title === 'string' && property.title
        ? property.title : humanizeWorkflowField(name),
      designerType,
      fileAccess: property['x-fengyu-file-access'] === 'read-write' ? 'read-write' : 'read',
      required: required.has(name),
      options: (property.enum ?? []).map(String),
      example: Array.isArray(property.examples) && property.examples[0] !== undefined
        ? String(property.examples[0]) : '',
      helpText: typeof property.description === 'string' ? property.description : '',
      hasDefault: property.default !== undefined,
      defaultText: formatDefaultValue(property.default, designerType),
    }
  })
}

function parseDefaultValue(field: DesignerField): unknown {
  switch (field.designerType) {
    case 'number': {
      const number = Number(field.defaultText)
      return Number.isFinite(number) ? number : undefined
    }
    case 'boolean': return field.defaultText === 'true'
    case 'array':
    case 'object': {
      try {
        const parsed = JSON.parse(field.defaultText)
        if (field.designerType === 'array') return Array.isArray(parsed) ? parsed : undefined
        return parsed && !Array.isArray(parsed) && typeof parsed === 'object' ? parsed : undefined
      } catch {
        return undefined
      }
    }
    case 'select': return field.options.includes(field.defaultText) || field.defaultText === ''
      ? field.defaultText
      : undefined
    default: return field.defaultText
  }
}

/** Serializes the designer rows back into the canonical schema text. */
export function buildDesignerSchemaText(schemaText: string, fields: DesignerField[]): string {
  const schema = parseSchema(schemaText)
  const previous = schema.properties ?? {}
  const properties: Record<string, DesignerSchemaProperty> = {}
  const required: string[] = []
  for (const field of fields) {
    // Start from the existing property so annotations the designer does not
    // model (x-fengyu-auto/-analyze/-enum/-options-from, nested items, ...)
    // survive an unrelated edit here.
    const source = previous[field.originalName] ?? previous[field.name]
    const property: DesignerSchemaProperty = source ? { ...source } : {}
    property.title = field.title || humanizeWorkflowField(field.name)
    // Designer-owned facets are cleared before re-applying so a type switch
    // (file/directory → number, select → string, ...) cannot leave them stale.
    // An enum is only designer-owned when it was what made the property a
    // "select" — enum-on-number and similar authored facets stay untouched.
    if ((source?.format === 'fengyu-file' || source?.format === 'fengyu-directory')
      && field.designerType !== 'file' && field.designerType !== 'directory') {
      delete property.format
      delete property['x-fengyu-file-access']
    }
    if (field.designerType !== 'textarea') delete property['x-fengyu-multiline']
    if (source && designerTypeOf(source) === 'select' && field.designerType !== 'select') {
      delete property.enum
    }
    const unchangedEnum = !!source?.enum
      && source.enum.map(String).join('\u0000') === field.options.join('\u0000')
    switch (field.designerType) {
      case 'number': property.type = 'number'; break
      case 'boolean': property.type = 'boolean'; break
      case 'array': property.type = 'array'; break
      case 'object': property.type = 'object'; break
      case 'file':
        property.type = 'string'
        property.format = 'fengyu-file'
        property['x-fengyu-file-access'] = 'read'
        break
      case 'directory':
        property.type = 'string'
        property.format = 'fengyu-directory'
        property['x-fengyu-file-access'] = field.fileAccess
        break
      case 'textarea': property.type = 'string'; property['x-fengyu-multiline'] = true; break
      case 'select':
        property.type = 'string'
        // Keep the parsed enum verbatim (it may hold non-string values) unless
        // the designer's options actually differ from what was read out of it.
        if (!unchangedEnum) property.enum = field.options.filter(Boolean)
        break
      default: property.type = 'string'; break
    }
    if (field.helpText) {
      property.description = field.helpText
    } else {
      delete property.description
    }
    if (field.example) {
      const originalExamples = source?.examples
      property.examples = Array.isArray(originalExamples) && originalExamples.length > 1
        && field.example === String(originalExamples[0])
        ? originalExamples
        : [field.example]
    } else {
      delete property.examples
    }
    if (field.hasDefault) {
      const defaultValue = parseDefaultValue(field)
      if (defaultValue !== undefined) property.default = defaultValue
      else delete property.default
    } else {
      delete property.default
    }
    properties[field.name] = property
    if (field.required) required.push(field.name)
  }
  return JSON.stringify({
    ...schema,
    type: 'object',
    properties,
    ...(required.length ? { required } : {}),
  }, null, 2)
}

/** Variable-name rule shared by the rename input and the duplicate check. */
export function normalizeDesignerName(raw: string): string {
  return raw.trim().replace(/[^A-Za-z0-9_-]/g, '')
}

/** Why one row's current name is not usable (empty / invalid chars / duplicate). */
export function designerNameProblem(
  fields: DesignerField[],
  index: number,
  rawName: string,
): 'empty' | 'invalid' | 'duplicate' | null {
  const name = normalizeDesignerName(rawName)
  if (!name) return 'empty'
  if (rawName.trim() !== name) return 'invalid'
  return fields.some((field, fieldIndex) => fieldIndex !== index && field.name === name)
    ? 'duplicate'
    : null
}
