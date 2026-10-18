import { ChevronDown } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { navigation, type Locale } from '@/content/navigation'
import { t } from '@/i18n'
import { cn } from '@/lib/utils'

/**
 * The sidebar mirrors scripts/navigation.mjs group-for-group. Long groups
 * (Plugins: 20 entries) auto-collapse unless they hold the active page or the
 * visitor expands them — short groups stay permanently open.
 */
const COLLAPSIBLE_THRESHOLD = 8

export function Sidebar({
  locale,
  pathname,
  open,
  onNavigate,
}: {
  locale: Locale
  pathname: string
  open: boolean
  onNavigate: () => void
}) {
  const sidebar = navigation[locale].sidebar
  const [expanded, setExpanded] = useState<Record<string, boolean>>({})
  const L = t(locale)

  return (
    <aside className={cn('doc-side', open && 'open')} aria-label={L.language}>
      {sidebar.map((group) => {
        const activeIndex = group.items.findIndex(
          (item) => item.link === pathname || pathname.startsWith(`${item.link.replace(/\/+$/, '')}/`),
        )
        const collapsible = group.items.length > COLLAPSIBLE_THRESHOLD
        const groupOpen = !collapsible || activeIndex >= 0 || expanded[group.text]
        return (
          <div className="doc-sidegroup" key={group.text}>
            {collapsible ? (
              <button
                type="button"
                className="doc-sidegroup-title doc-sidegroup-toggle"
                aria-expanded={groupOpen}
                onClick={() => setExpanded((state) => ({ ...state, [group.text]: !groupOpen }))}
              >
                {group.text}
                <ChevronDown size={12} className={cn('doc-sidegroup-chev', groupOpen && 'up')} />
              </button>
            ) : (
              <p className="doc-sidegroup-title">{group.text}</p>
            )}
            {groupOpen &&
              group.items.map((item) => {
                const link = item.link.replace(/\/+$/, '') || `/${locale}`
                const active = link === pathname
                return (
                  <Link key={item.link} to={link} className={cn('doc-sidelink', active && 'active')} onClick={onNavigate}>
                    {item.text}
                  </Link>
                )
              })}
          </div>
        )
      })}
    </aside>
  )
}
