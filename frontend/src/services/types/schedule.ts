export interface CalendarSchedule {
  frequency: 'DAILY' | 'WEEKLY' | 'MONTHLY'
  time: string
  zoneId: string
  weekdays?: number[]
  monthDay?: number
}

/** Five-field cron expression (minute hour day-of-month month day-of-week), server-local time. */
export interface CronSchedule {
  expression: string
}

export interface AgentScheduleSummary {
  calendar?: CalendarSchedule | null
  cron?: CronSchedule | null
  scheduleId: string
  workflowId: string
  intervalSeconds: number
  recurring: boolean
  nextFireAt: string
  fires: number
  /** Overdue occurrences coalesced into recovery fires while the app was stopped. */
  missedFires: number
  lastFireAt?: string | null
  lastTaskId?: string | null
  lastError?: string | null
  createdAt: string
  expiresAt: string | null
  persistent: boolean
  sandboxProfile: 'sandboxed' | 'unsandboxed'
}
