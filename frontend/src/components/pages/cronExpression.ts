type Translate = (key: string, params?: Record<string, unknown>) => string

/** Field order of the unix five-field cron form this app accepts. */
const FIELD_MIN = [0, 0, 1, 1, 0]
const FIELD_MAX = [59, 23, 31, 12, 7]
const MONTH_NAMES = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC']
const DAY_NAMES = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT']

/**
 * Client-side mirror of the backend's five-field cron grammar (CronSchedule.java):
 * field count, per-field ranges, steps and name lists are checked so the form can hint
 * at the exact problem while typing. The backend stays authoritative — its message
 * replaces this hint if a hand-crafted request slips past.
 */
export function cronValidationError(
  expression: string,
  t: Translate,
): string | null {
  const fields = expression.trim().split(/\s+/).filter(Boolean)
  if (fields.length !== 5) {
    return t('schedules.cronFieldCount', { n: fields.length })
  }
  const labels = [
    t('schedules.cronFieldMinute'),
    t('schedules.cronFieldHour'),
    t('schedules.cronFieldDayOfMonth'),
    t('schedules.cronFieldMonth'),
    t('schedules.cronFieldDayOfWeek'),
  ]
  for (let index = 0; index < 5; index++) {
    const problem = fieldProblem(index, fields[index], t)
    if (problem) return t('schedules.cronFieldInvalid', { field: labels[index], problem })
  }
  return null
}

function fieldProblem(index: number, field: string, t: Translate): string | null {
  const dayField = index === 2 || index === 4
  for (const atom of field.split(',')) {
    if (!atom) return t('schedules.cronProblemEmpty')
    if (atom === '*' || (atom === '?' && dayField)) continue
    const [range, step, ...extra] = atom.split('/')
    if (extra.length > 0) return t('schedules.cronProblemSlashes', { atom })
    if (step !== undefined && (!/^\d{1,3}$/.test(step) || Number(step) < 1)) {
      return t('schedules.cronProblemStep', { atom })
    }
    if (range === '*' || (range === '?' && dayField)) continue
    const bounds = range.split('-')
    if (bounds.length > 2) return t('schedules.cronProblemDashes', { atom })
    const names = index === 3 ? MONTH_NAMES : index === 4 ? DAY_NAMES : []
    const numeric = (raw: string): number | null =>
      /^\d{1,2}$/.test(raw) ? Number(raw) : null
    const first = numeric(bounds[0])
    const last = bounds.length === 2 ? numeric(bounds[1]) : first
    if (first === null || last === null) {
      if (bounds.length === 1 && names.includes(bounds[0].toUpperCase())) continue
      if (bounds.length === 2 && names.includes(bounds[0].toUpperCase())
          && names.includes(bounds[1].toUpperCase())) {
        return t('schedules.cronProblemNameRange')
      }
      return t('schedules.cronProblemValue', { range })
    }
    if (first < FIELD_MIN[index] || first > FIELD_MAX[index]
        || last < FIELD_MIN[index] || last > FIELD_MAX[index]) {
      return t('schedules.cronProblemRange', {
        value: first < FIELD_MIN[index] || first > FIELD_MAX[index] ? first : last,
        min: FIELD_MIN[index],
        max: FIELD_MAX[index],
      })
    }
    if (bounds.length === 2 && first > last) {
      return t('schedules.cronProblemOrder', { atom })
    }
  }
  return null
}
