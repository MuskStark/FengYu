import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Brain, Search, Trash2 } from 'lucide-react'
import { services } from '@/services'

interface MemoryRow {
  id: string
  content: string
  topics?: string[]
}

/**
 * AI memory viewer (Settings): lists what the assistant has remembered about the user and
 * lets them delete stale entries — read/manage over the (experimental) keyword memory
 * store, no model tools involved. Empty state doubles as the "memory disabled" state.
 */
export default function MemorySection() {
  const { t } = useTranslation()
  const [rows, setRows] = useState<MemoryRow[]>([])
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(true)

  const load = useCallback(async (search: string) => {
    setLoading(true)
    try {
      const data = await services.chat.listMemory(search.trim() || undefined)
      setRows(data.map(row => ({
        id: String(row.id),
        content: String(row.content ?? ''),
        topics: Array.isArray(row.topics)
          ? row.topics.filter((topic): topic is string => typeof topic === 'string')
          : undefined,
      })))
    } catch {
      setRows([])
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load('') }, [load])

  async function forget(id: string): Promise<void> {
    try {
      await services.chat.forgetMemory(id)
      setRows(current => current.filter(row => row.id !== id))
    } catch {
      /* deletion failure keeps the row; the next load reconciles */
    }
  }

  return (
    <section>
      <h2 className="set-h">{t('settings.memoryTitle')}</h2>
      <div className="cx-card">
        <div className="cx-setting-row">
          <div className="cx-setting-row__label">
            <Brain size={16} />
            <span>{t('settings.memoryHint')}</span>
          </div>
        </div>
        <div className="memory-toolbar">
          <div className="memory-search">
            <Search size={14} />
            <input
              value={query}
              placeholder={t('settings.memorySearchPlaceholder')}
              onChange={event => setQuery(event.target.value)}
              onKeyDown={event => {
                if (event.key === 'Enter') void load(query)
              }}
            />
          </div>
        </div>
        {loading ? (
          <div className="cx-muted memory-status"><span className="cx-spin" /></div>
        ) : rows.length === 0 ? (
          <div className="cx-muted memory-status">{t('settings.memoryEmpty')}</div>
        ) : (
          <ul className="memory-list">
            {rows.map(row => (
              <li key={row.id} className="memory-row">
                <div className="memory-row__body">
                  <div className="memory-row__content">{row.content}</div>
                  {row.topics && row.topics.length > 0 && (
                    <div className="memory-row__topics">
                      {row.topics.map(topic => (
                        <span key={topic} className="cx-chip">{topic}</span>
                      ))}
                    </div>
                  )}
                </div>
                <button
                  type="button"
                  className="cx-iconbtn cx-iconbtn--sm"
                  title={t('settings.memoryForget')}
                  onClick={() => void forget(row.id)}
                >
                  <Trash2 size={14} />
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  )
}
