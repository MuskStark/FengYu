/**
 * Pure model behind the inspector's connection dropdown: a tool input opts in through two
 * JSON-Schema annotations — `x-fengyu-connection-type` (which profiles qualify) and the
 * optional `x-fengyu-connection-field` (which profile field feeds the argument; defaults to
 * the profile's first field). Picking a profile writes that field's VALUE into the node
 * argument, so the executor needs no connection awareness at all.
 */
import type { ConnectionProfile } from '@/services/connections'

export interface ConnectionAnnotation {
  type: string
  field: string | null
}

/** Normalizes the annotation the same way the backend normalizes profile types. */
export function parseConnectionAnnotation(
  schema: Record<string, unknown> | null | undefined,
): ConnectionAnnotation | null {
  const rawType = schema?.['x-fengyu-connection-type']
  if (typeof rawType !== 'string') return null
  const type = rawType.trim().toLowerCase().replace(/[^a-z0-9-]/g, '')
  if (!type) return null
  const rawField = schema?.['x-fengyu-connection-field']
  const field = typeof rawField === 'string' && rawField.trim() ? rawField.trim() : null
  return { type, field }
}

/** The value a profile contributes for this annotation (null when it has nothing usable). */
export function profileValueFor(
  profile: ConnectionProfile,
  annotation: ConnectionAnnotation,
): string | null {
  const keys = Object.keys(profile.fields ?? {})
  const key = annotation.field && keys.includes(annotation.field) ? annotation.field : keys[0]
  if (!key) return null
  const value = profile.fields[key]
  return typeof value === 'string' && value !== '' ? value : null
}

/** Which profile the current argument value came from (null = manual / unknown). */
export function selectedProfileId(
  profiles: ConnectionProfile[],
  annotation: ConnectionAnnotation,
  currentValue: unknown,
): string | null {
  if (typeof currentValue !== 'string' || !currentValue) return null
  for (const profile of profiles) {
    if (profileValueFor(profile, annotation) === currentValue) return profile.id
  }
  return null
}

/** Draft for the in-place "create a connection" mini form. */
export function connectionDraftFor(
  annotation: ConnectionAnnotation,
  name: string,
  value: string,
): { name: string; type: string; fields: Record<string, string> } {
  return {
    name: name.trim(),
    type: annotation.type,
    fields: { [annotation.field ?? 'value']: value },
  }
}
