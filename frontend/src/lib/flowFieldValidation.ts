import { i18n } from '@/i18n'

/**
 * Field-level manual-input validation for the flow inspector: JSON-Schema-ish
 * constraints (min/max/exclusive bounds, integer, maxLength, pattern, enum
 * membership) checked live while typing. References and expressions are exempt
 * here — they carry their own resolution warnings in the inspector.
 */

export interface FieldValidationSchema {
  type?: string
  format?: string
  enum?: unknown[]
  minimum?: number
  maximum?: number
  exclusiveMinimum?: number
  exclusiveMaximum?: number
  multipleOf?: number
  maxLength?: number
  pattern?: string
  items?: { type?: string }
}

export type NumberParse =
  | { ok: true; value: number }
  | { ok: false }

/** Parses manual number input; a non-finite/garbage string is an error, never a silent 0. */
export function parseManualNumber(raw: string): NumberParse {
  const trimmed = raw.trim()
  if (!trimmed) return { ok: false }
  const parsed = Number(trimmed)
  return Number.isFinite(parsed) ? { ok: true, value: parsed } : { ok: false }
}

/** Raw display text of a value for validation purposes (objects serialize). */
function asText(value: unknown): string | null {
  if (value === undefined || value === null) return null
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return null
}

/**
 * Validates one manually-entered field value against its schema. Returns a
 * localized message, or null when the value is empty (required-ness is a
 * separate, node-level diagnostic) or valid.
 */
export function validateManualField(schema: FieldValidationSchema, value: unknown): string | null {
  const t = i18n.global.t
  if (value === undefined || value === null || value === '') return null
  const isNumber = schema.type === 'number' || schema.type === 'integer'

  if (isNumber) {
    if (typeof value !== 'number' || !Number.isFinite(value)) {
      return t('flows.validationNotANumber')
    }
    if (schema.type === 'integer' && !Number.isInteger(value)) {
      return t('flows.validationNotAnInteger')
    }
    if (schema.minimum !== undefined && value < schema.minimum) {
      return t('flows.validationMin', { min: String(schema.minimum) })
    }
    if (schema.maximum !== undefined && value > schema.maximum) {
      return t('flows.validationMax', { max: String(schema.maximum) })
    }
    if (schema.exclusiveMinimum !== undefined && value <= schema.exclusiveMinimum) {
      return t('flows.validationExclusiveMin', { min: String(schema.exclusiveMinimum) })
    }
    if (schema.exclusiveMaximum !== undefined && value >= schema.exclusiveMaximum) {
      return t('flows.validationExclusiveMax', { max: String(schema.exclusiveMaximum) })
    }
    if (schema.multipleOf !== undefined && schema.multipleOf > 0) {
      const quotient = value / schema.multipleOf
      if (Math.abs(quotient - Math.round(quotient)) > 1e-9) {
        return t('flows.validationMultipleOf', { multiple: String(schema.multipleOf) })
      }
    }
    return null
  }

  const text = asText(value)
  if (text === null) return null
  if (schema.maxLength !== undefined && text.length > schema.maxLength) {
    return t('flows.validationMaxLength', { max: String(schema.maxLength) })
  }
  if (schema.pattern) {
    try {
      if (!new RegExp(schema.pattern).test(text)) {
        return t('flows.validationPattern', { pattern: schema.pattern })
      }
    } catch {
      // An authoring-side invalid pattern must not break input — ignore it.
    }
  }
  if (schema.enum?.length
    && schema.type !== 'number' && schema.type !== 'integer'
    && !schema.enum.some((option) =>
      (typeof option === 'object' && option !== null && 'value' in option
        ? (option as { value: unknown }).value : option) === value)) {
    return t('flows.validationEnum')
  }
  return null
}

/** Whether a manual string parses as the array-item type the schema declares. */
export function arrayItemParseError(schema: FieldValidationSchema, raw: string): string | null {
  const itemType = schema.items?.type
  if (itemType !== 'number' && itemType !== 'integer') return null
  for (const item of raw.split(/[,\n]/).map((entry) => entry.trim()).filter(Boolean)) {
    const parsed = parseManualNumber(item)
    if (!parsed.ok) return i18n.global.t('flows.validationArrayItemNumber')
    if (itemType === 'integer' && !Number.isInteger(parsed.value)) {
      return i18n.global.t('flows.validationArrayItemInteger')
    }
  }
  return null
}
