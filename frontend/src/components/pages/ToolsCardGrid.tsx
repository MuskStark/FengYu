import { useEffect, useId, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AnimatePresence, motion, MotionConfig } from 'motion/react'
import {
  ArrowLeftRight, ArrowUpRight, Code, FolderOpen, Image as ImageIcon, Network,
  Puzzle, Sparkles, Star, Trash2, Type, X,
} from 'lucide-react'
import type { PluginDescriptor } from '@/services/types'
import { cn } from '@/lib/utils'
import { ToggleSwitch } from './ToggleSwitch'

/**
 * Tools-page card grid with in-place card expansion (pattern:
 * ui.aceternity.com/components/expandable-card — reimplemented on motion +
 * our token palette, SpotlightCard-port precedent). Shared layoutIds morph a
 * grid card into the centered detail layer: card surface, glyph tile, title,
 * and the grid's 详情 button (which lands on the expanded view's primary
 * action) all fly; the detail block fades in behind them. Interaction split
 * follows the demo: clicking the card opens the plugin, clicking the 详情
 * button expands; ESC and outside-click close. Category identity is a line
 * glyph on a neutral tile — gold stays reserved for interactive fills.
 */

const CATEGORY_ICONS: Record<string, typeof Puzzle> = {
  net: Network,
  text: Type,
  image: ImageIcon,
  dev: Code,
  ai: Sparkles,
  file: FolderOpen,
  transfer: ArrowLeftRight,
}

function categoryIcon(category: string) {
  return CATEGORY_ICONS[category.toLowerCase()] ?? Puzzle
}

function categoryLabel(t: (key: string) => string, category: string): string {
  const key = `category.${category.toLowerCase()}`
  const translated = t(key)
  return translated === key ? category : translated
}

export interface ToolsCardGridProps {
  plugins: PluginDescriptor[]
  favorites: ReadonlySet<string>
  busyId: string | null
  onToggleFavorite: (id: string) => void
  onOpen: (id: string) => void
  onUninstall: (plugin: PluginDescriptor) => void
  onToggleEnabled: (plugin: PluginDescriptor) => void
}

export function ToolsCardGrid({
  plugins, favorites, busyId, onToggleFavorite, onOpen, onUninstall, onToggleEnabled,
}: ToolsCardGridProps) {
  const { t } = useTranslation()
  const [activeId, setActiveId] = useState<string | null>(null)
  /** Derived from the live list: an uninstalled plugin drops out and the layer exits by itself. */
  const active = plugins.find(plugin => plugin.id === activeId) ?? null
  const layerRef = useRef<HTMLDivElement>(null)
  const closeRef = useRef<HTMLButtonElement>(null)
  const uid = useId()

  const lid = (part: string, id: string) => `${part}-${id}-${uid}`

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setActiveId(null)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  /** Focus lands on the close button when the dialog opens (id-keyed so data
   * reloads inside an open dialog don't re-steal focus). */
  useEffect(() => {
    if (activeId) closeRef.current?.focus()
  }, [activeId])

  /** While open: lock body scroll and close on any pointer-down outside the card. */
  useEffect(() => {
    if (!active) return
    document.body.style.overflow = 'hidden'
    const onDown = (event: MouseEvent | TouchEvent) => {
      if (layerRef.current && !layerRef.current.contains(event.target as Node)) setActiveId(null)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('touchstart', onDown)
    return () => {
      document.body.style.overflow = ''
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('touchstart', onDown)
    }
  }, [active])

  return (
    <MotionConfig reducedMotion="user">
      <>
      <AnimatePresence>
        {active && (
          <motion.div
            className="tools-x-backdrop"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
          />
        )}
      </AnimatePresence>

      <AnimatePresence>
        {active && (() => {
          const Icon = categoryIcon(active.category)
          const enabled = active.enabled !== false
          const busy = busyId === active.id
          return (
            <div className="tools-x-overlay" role="dialog" aria-modal="true" aria-label={active.name}>
              <motion.div layoutId={lid('card', active.id)} ref={layerRef} className="tools-x-card">
                <button
                  ref={closeRef}
                  className="cx-iconbtn tools-x-close"
                  aria-label={t('common.close')}
                  onClick={() => setActiveId(null)}
                ><X size={16} /></button>
                <div className="tools-x-head">
                  <motion.span layoutId={lid('glyph', active.id)} className="pg-icon pg-icon--flat tools-x-glyph">
                    <Icon size={23} strokeWidth={1.6} />
                  </motion.span>
                  <div className="pg-card-titlewrap">
                    <motion.div layoutId={lid('title', active.id)} className="tools-x-title">{active.name}</motion.div>
                  </div>
                </div>
                <motion.div
                  layout
                  initial={{ opacity: 0 }}
                  animate={{ opacity: 1 }}
                  exit={{ opacity: 0, transition: { duration: 0.05 } }}
                  className="tools-x-body"
                >
                  <div className="pg-card-meta">
                    {categoryLabel(t, active.category)} · <span className="pg-version">v{active.version}</span>
                    {` · ${active.source === 'OFFICIAL' ? t('source.official') : t('source.third_party')}`}
                    {active.supportsAi ? ` · ${t('badge.ai')}` : ''}
                    {!enabled && ` · ${t('store.sources.disable')}`}
                  </div>
                  <motion.p layoutId={lid('desc', active.id)} className="tools-x-desc">{active.description}</motion.p>
                  <div className="pg-detail">
                    <div className="pg-detail__row">
                      <span className="pg-detail__label">{t('store.publisher')}</span>
                      <span className="pg-detail__value">{active.author || '—'}</span>
                    </div>
                    {active.permissions && active.permissions.length > 0 && (
                      <div className="pg-detail__row">
                        <span className="pg-detail__label">{t('store.permissions')}</span>
                        <span className="pg-card-row">
                          {active.permissions.map(permission => (
                            <code key={permission} className="cx-chip">{permission}</code>
                          ))}
                        </span>
                      </div>
                    )}
                    <div className="pg-detail__row">
                      <span className="pg-detail__label">ID</span>
                      <span className="pg-detail__value"><code>{active.id}</code></span>
                    </div>
                    <div className="tools-x-actions">
                      <ToggleSwitch
                        checked={enabled}
                        disabled={busy}
                        title={t('grid.toggleEnabled')}
                        onChange={() => onToggleEnabled(active)}
                      />
                      <span className="spacer" />
                      <button
                        className="cx-btn cx-btn--danger"
                        disabled={busy}
                        onClick={() => onUninstall(active)}
                      >
                        {busy && <span className="cx-spin" />}
                        <Trash2 size={15} />
                        {t('store.sources.uninstall')}
                      </button>
                      <motion.button
                        layoutId={lid('action', active.id)}
                        className="cx-btn cx-btn--primary"
                        disabled={busy}
                        onClick={() => onOpen(active.id)}
                      >
                        <ArrowUpRight size={15} />
                        {t('grid.open')}
                      </motion.button>
                    </div>
                  </div>
                </motion.div>
              </motion.div>
            </div>
          )
        })()}
      </AnimatePresence>

      <div className="pg-grid">
        {plugins.map(plugin => {
          const Icon = categoryIcon(plugin.category)
          const faved = favorites.has(plugin.id)
          const enabled = plugin.enabled !== false
          return (
            <motion.div
              key={plugin.id}
              layoutId={lid('card', plugin.id)}
              className="tools-card"
              role="button"
              tabIndex={0}
              onClick={() => onOpen(plugin.id)}
              onKeyDown={event => {
                if (event.target !== event.currentTarget) return
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault()
                  onOpen(plugin.id)
                }
              }}
            >
              <div className="tools-card-head">
                <motion.span layoutId={lid('glyph', plugin.id)} className="pg-icon pg-icon--flat">
                  <Icon size={19} strokeWidth={1.6} />
                </motion.span>
                <div className="pg-card-titlewrap">
                  <motion.div layoutId={lid('title', plugin.id)} className="tools-card-title">
                    <span title={plugin.name}>{plugin.name}</span>
                  </motion.div>
                  <div className="pg-card-meta">
                    {categoryLabel(t, plugin.category)} · v{plugin.version}
                  </div>
                </div>
                <button
                  className={cn('cx-iconbtn cx-iconbtn--sm pg-tool-fav', faved && 'pg-tool-fav--faved')}
                  title={t('grid.toggleFavorite')}
                  aria-label={t('grid.toggleFavorite')}
                  aria-pressed={faved}
                  onClick={event => {
                    event.stopPropagation()
                    onToggleFavorite(plugin.id)
                  }}
                ><Star size={16} fill={faved ? 'currentColor' : 'none'} /></button>
              </div>
              <div className="pg-card-desc-wrap"><motion.p layoutId={lid('desc', plugin.id)} className="pg-card-desc">{plugin.description}</motion.p></div>
              <div className="tools-card-foot">
                <span className={cn('cx-chip', plugin.source === 'OFFICIAL' ? 'cx-chip--gold' : undefined)}>
                  {plugin.source === 'OFFICIAL' ? t('source.official') : t('source.third_party')}
                </span>
                {plugin.supportsAi && <span className="cx-chip cx-chip--success">{t('badge.ai')}</span>}
                {!enabled && <span className="cx-chip cx-chip--warn">{t('store.sources.disable')}</span>}
                <span className="spacer" />
                <motion.button
                  layoutId={lid('action', plugin.id)}
                  className="cx-btn cx-btn--outline cx-btn--sm"
                  aria-haspopup="dialog"
                  onClick={event => {
                    event.stopPropagation()
                    setActiveId(plugin.id)
                  }}
                >{t('grid.toggleDetail')}</motion.button>
              </div>
            </motion.div>
          )
        })}
      </div>
      </>
    </MotionConfig>
  )
}
