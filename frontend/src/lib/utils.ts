import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

/** Tailwind-aware class merge (shadcn convention; Aceternity components use it too). */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/**
 * Locale-aware date-time for the UI surfaces (run history, schedules, webhooks).
 * `language` is the i18n locale ('en' | 'zh' — both valid BCP-47 tags); empty falls
 * back to the host default, matching the old bare toLocaleString() behavior.
 */
export function formatDateTime(value: string | number | Date, language: string): string {
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString(language || undefined)
}
