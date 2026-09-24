import { useTranslation } from 'react-i18next'
import { cn } from '@/lib/utils'

/**
 * The bee ladder's visual identity (Infinia Level 标识), ported from the Vue
 * shell's account/beeLevels.ts: each hive level carries its own emblem and
 * tone, mapped onto the cx-chip variants.
 */
export type BeeTone = 'muted' | 'accent' | 'success' | 'danger' | 'gold'

export interface BeeMark {
  level: number
  emblem: string
  tone: BeeTone
}

const BEE_MARKS: Record<number, { emblem: string; tone: BeeTone }> = {
  0: { emblem: '🥚', tone: 'muted' }, // Larva — still in the cell
  1: { emblem: '🐝', tone: 'accent' }, // Worker — the hive's baseline
  2: { emblem: '🍯', tone: 'success' }, // Forager — brings home nectar
  3: { emblem: '🛡️', tone: 'danger' }, // Guard — defends the entrance
  4: { emblem: '👑', tone: 'gold' }, // Queen — rules the hive
}

export const BEE_LEVELS = [0, 1, 2, 3, 4] as const

export function beeMark(level: number): BeeMark {
  const raw = Math.trunc(level)
  const clamped = Number.isFinite(raw) ? Math.max(0, Math.min(4, raw)) : 0
  const mark = BEE_MARKS[clamped] ?? BEE_MARKS[0]
  return { level: clamped, ...mark }
}

const BEE_TONE_CHIP: Record<BeeTone, string> = {
  muted: '',
  accent: 'cx-chip--primary',
  success: 'cx-chip--success',
  danger: 'cx-chip--error',
  gold: 'cx-chip--gold',
}

/** Level badge: emblem + localized level name + Lv number (Vue BeeLevelBadge twin). */
export function BeeLevelBadge({ level }: { level: number }) {
  const { t } = useTranslation()
  const mark = beeMark(level)
  return (
    <span className={cn('cx-chip', BEE_TONE_CHIP[mark.tone])}>
      <span aria-hidden="true">{mark.emblem}</span>
      {t(`account.beeLevel.${mark.level}`)} · Lv{mark.level}
    </span>
  )
}
