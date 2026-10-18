import manifestJson from '../.content/manifest.json'
import type { Locale } from './navigation'

export interface TocItem {
  depth: number
  text: string
  id: string
}

export interface RouteMeta {
  key: string
  locale: Locale
  title: string
  description: string
  lang: string
  changelog: boolean
  toc: TocItem[]
  updatedAt: string | null
}

export interface Manifest {
  generatedAt: string
  siteUrl: string
  routes: Record<string, RouteMeta>
}

export const manifest = manifestJson as Manifest

export interface ChangelogVersion {
  version: string
  date: string
  anchor: string
  html: string
}

export interface PageData {
  frontmatter: {
    title?: string
    description?: string
    lang?: string
    layout?: string
  }
  html: string
  /** Anchor id of the body's dropped leading `# H1` (kept alive on the shell's display heading). */
  titleId: string | null
  toc: TocItem[]
  changelog?: { versions: ChangelogVersion[] }
}

const pageModules = import.meta.glob('/src/.content/pages/**/*.json') as Record<
  string,
  () => Promise<{ default: PageData }>
>

/** page key ('en/guide/ai-chat', 'en/index') → canonical route ('/en/guide/ai-chat', '/en'). */
export function keyToRoute(key: string): string {
  return `/${key.replace(/\/index$/, '')}`
}

export function loadPage(route: string): Promise<PageData> | undefined {
  const meta = manifest.routes[route]
  if (!meta) return undefined
  const loader = pageModules[`/src/.content/pages/${meta.key}.json`]
  return loader?.().then((module) => module.default)
}

export interface FlatEntry {
  route: string
  text: string
  group: string
}

/** Sidebar order flattened — breadcrumbs (group label) and prev/next pager derive from it. */
export function flatSidebar(locale: Locale, sidebar: { text: string; items: { text: string; link: string }[] }[]): FlatEntry[] {
  const seen = new Set<string>()
  const entries: FlatEntry[] = []
  for (const group of sidebar) {
    for (const item of group.items) {
      const route = item.link.replace(/\/+$/, '') || `/${locale}`
      if (seen.has(route)) continue
      seen.add(route)
      entries.push({ route, text: item.text, group: group.text })
    }
  }
  return entries
}

export function pagerFor(route: string, flat: FlatEntry[]): { prev?: FlatEntry; next?: FlatEntry } {
  const index = flat.findIndex((entry) => entry.route === route)
  if (index < 0) return {}
  return { prev: flat[index - 1], next: flat[index + 1] }
}
