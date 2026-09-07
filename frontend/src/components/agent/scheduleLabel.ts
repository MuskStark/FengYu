import type { AgentScheduleSummary } from '@/api/types'

type Translate = (key: string, values: Record<string, unknown>) => string

export function scheduleLabel(schedule: Pick<AgentScheduleSummary, 'calendar' | 'recurring' | 'intervalSeconds'>, t: Translate): string {
  const rule = schedule.calendar
  if (!rule) {
    const seconds = schedule.intervalSeconds
    if (seconds % 60 !== 0) return t(schedule.recurring ? 'agent.scheduleEvery' : 'schedules.onceAfter', { n: seconds })
    const divisor = seconds % 3600 === 0 ? 3600 : 60
    return t(schedule.recurring ? 'schedules.intervalSummary' : 'schedules.delaySummary', {
      n: seconds / divisor, unit: t(divisor === 3600 ? 'schedules.hours' : 'schedules.minutes', {}),
    })
  }
  const days = [...(rule.weekdays ?? [])].sort((a, b) => a - b)
    .map((day) => t(`schedules.weekday${day}`, {})).join(' / ')
  const day = rule.monthDay === -1 ? t('schedules.lastDay', {}) : t('schedules.dayOfMonth', { day: rule.monthDay })
  return t(`schedules.summary${rule.frequency}`, { time: rule.time, days, day, zone: rule.zoneId })
}
