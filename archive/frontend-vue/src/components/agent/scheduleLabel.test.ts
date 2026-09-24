import { describe, expect, it } from 'vitest'
import { createI18n } from 'vue-i18n'
import en from '@/i18n/en.json'
import zh from '@/i18n/zh.json'
import { scheduleLabel } from './scheduleLabel'

const t = createI18n({ legacy: false, locale: 'zh', messages: { en, zh } }).global.t

describe('schedule descriptions', () => {
  it('describes weekly clock time and time zone rather than a seconds interval', () => {
    expect(scheduleLabel({ intervalSeconds: 60, recurring: true, calendar: {
      frequency: 'WEEKLY', time: '09:00', zoneId: 'Asia/Shanghai', weekdays: [5, 1],
    } }, t)).toBe('每周 周一 / 周五 09:00（Asia/Shanghai）')
  })
  it('shows the last day of each month', () => {
    expect(scheduleLabel({ intervalSeconds: 60, recurring: true, calendar: {
      frequency: 'MONTHLY', time: '18:00', zoneId: 'UTC', monthDay: -1,
    } }, t)).toBe('每月 最后一天，18:00（UTC）')
  })
  it('keeps legacy schedules readable in minutes and hours', () => {
    expect(scheduleLabel({ intervalSeconds: 3600, recurring: true }, t)).toBe('每 1 小时')
    expect(scheduleLabel({ intervalSeconds: 120, recurring: false }, t)).toBe('2 分钟后执行一次')
    expect(scheduleLabel({ intervalSeconds: 90, recurring: true }, t)).toBe('每 90 秒')
  })
})
