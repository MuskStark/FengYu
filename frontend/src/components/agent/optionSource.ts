import { api } from '@/api/client'
import type {
  FlowNodeContext,
  FlowNodeContextFeed,
  FlowNodeOptionSource,
} from '@/api/types'

/**
 * Unified option-source machinery for flow-node inputs — one standard serving
 * three kinds of candidates:
 *  - static `options` on the declaration (no calls)
 *  - CATALOG sources (`source`): options fetched once from a plugin list
 *    method, mapped through value/label; the same fetch backs the run form's
 *    x-fengyu-enum annotations (semantic convergence, one code path)
 *  - CONTEXT sources (`context`): datasets derived at edit time from another
 *    input's value (e.g. workbook path → sheets/columns via analyze), consumed
 *    through `optionsFromContext` references — including row-field keying
 */

export interface CatalogOption {
  value: unknown
  label: string
}

/** Maps one plugin list result onto display options (pure — unit-tested). */
export function mapCatalogOptions(
  result: unknown,
  spec: Pick<FlowNodeOptionSource, 'items' | 'value' | 'label' | 'labelSecondary'>,
): CatalogOption[] {
  const container = result as Record<string, unknown> | null
  const list = spec.items ? container?.[spec.items] : result
  if (!Array.isArray(list)) return []
  return list
    .filter((item): item is Record<string, unknown> => !!item && typeof item === 'object')
    .map((item) => ({
      value: item[spec.value],
      label: [item[spec.label], spec.labelSecondary ? item[spec.labelSecondary] : undefined]
        .filter((part) => part !== undefined && part !== null && part !== '')
        .join(' · '),
    }))
}

/** Fetches catalog options through the plugin's JSON-RPC worker channel. */
export async function fetchCatalogOptions(
  pluginId: string | null | undefined,
  spec: FlowNodeOptionSource,
): Promise<CatalogOption[]> {
  if (!pluginId) return []
  const result = await api.invokePluginMethod<Record<string, unknown>>(pluginId, spec.method)
  return mapCatalogOptions(result, spec)
}

/**
 * Renders a context method's params: "{{value}}" templates the triggering
 * input's current value; anything else passes through verbatim.
 */
export function renderContextParams(
  params: Record<string, string> | undefined,
  value: unknown,
): Record<string, unknown> {
  const rendered: Record<string, unknown> = {}
  for (const [key, template] of Object.entries(params ?? {})) {
    rendered[key] = template === '{{value}}' ? value : template
  }
  return rendered
}

export type ContextFeedValue = string[] | Record<string, string[]>

/**
 * Extracts every declared feed from a context method's result. Flat feeds
 * ({list, item}) yield string lists; keyed feeds ({list, key, items, itemField})
 * yield a map keyed by the grouping field (e.g. sheet name → column names).
 */
export function parseContextFeeds(
  result: unknown,
  feeds: Record<string, FlowNodeContextFeed>,
): Record<string, ContextFeedValue> {
  const container = (result ?? {}) as Record<string, unknown>
  const parsed: Record<string, ContextFeedValue> = {}
  for (const [name, feed] of Object.entries(feeds)) {
    const list = container[feed.list]
    if (!Array.isArray(list)) continue
    const entries = list.filter(
      (item): item is Record<string, unknown> => !!item && typeof item === 'object',
    )
    if (feed.key) {
      const keyed: Record<string, string[]> = {}
      for (const entry of entries) {
        const key = entry[feed.key]
        if (typeof key !== 'string' && typeof key !== 'number') continue
        const nested = feed.items ? entry[feed.items] : null
        const values = Array.isArray(nested)
          ? nested
              .filter((item): item is Record<string, unknown> => !!item && typeof item === 'object')
              .map((item) => {
                const field = feed.itemField ?? feed.item
                const value = field ? item[field] : item
                return value === undefined || value === null ? '' : String(value)
              })
              .filter(Boolean)
          : []
        keyed[String(key)] = values
      }
      parsed[name] = keyed
    } else if (feed.item) {
      parsed[name] = entries
        .map((entry) => entry[feed.item!])
        .filter((value): value is string | number => typeof value === 'string' || typeof value === 'number')
        .map(String)
    }
  }
  return parsed
}

/**
 * Resolves one optionsFromContext reference against the parsed feeds: a keyed
 * bucket when keyedBy's current value matches, the union of all buckets as a
 * pre-fill fallback, or the flat list otherwise.
 */
export function contextFeedOptions(
  feeds: Record<string, ContextFeedValue>,
  spec: { set: string; keyedBy?: string } | undefined,
  rowKeyValue?: unknown,
): string[] {
  if (!spec) return []
  const feed = feeds[spec.set]
  if (!feed) return []
  if (Array.isArray(feed)) return feed
  if (spec.keyedBy && typeof rowKeyValue === 'string' && rowKeyValue) {
    const bucket = feed[rowKeyValue]
    if (bucket && bucket.length) return bucket
  }
  // A row whose key is not (yet) picked still sees the union, so the user can
  // pre-fill a value and pick the key afterwards.
  return [...new Set(Object.values(feed).flat())]
}

/** Runs one context source for a node input (the analyze-style trigger). */
export async function runNodeContext(options: {
  pluginId: string | null | undefined
  nodeId: string
  context: FlowNodeContext
  value: unknown
}): Promise<Record<string, ContextFeedValue>> {
  const params = renderContextParams(options.context.params, options.value) as Record<string, unknown>
  if (options.context.sessionScope !== 'node') {
    // Canvas fetches always isolate: a dedicated per-node session keeps edit-time
    // analysis from polluting chat or run split sessions.
    Object.assign(params, {})
  }
  params.session = `canvas-${options.nodeId}`
  const result = await api.invokePluginMethod<Record<string, unknown>>(
    options.pluginId ?? '', options.context.method, params,
  )
  return parseContextFeeds(result, options.context.feeds)
}

/**
 * Edit-time context state machine (implementation plan §8.4): one controller per
 * node input keeps the analyze lifecycle honest —
 *  - a monotonically increasing request sequence drops stale responses, so a slow
 *    analyze for an OLD source value can never overwrite a newer run's feeds;
 *  - `invalidate()` fires when the source value changes: feeds clear immediately
 *    (no stale candidates remain pickable) and `stale` marks dependent candidate
 *    values as needing re-analysis;
 *  - a failed run clears feeds and records the error but NEVER touches the
 *    user-authored argument values (those live in the inspector, not here).
 */
export interface ContextFeedState {
  feeds: Record<string, ContextFeedValue>
  running: boolean
  error: string | null
  stale: boolean
}

export class ContextFeedController {
  readonly state: ContextFeedState = { feeds: {}, running: false, error: null, stale: false }
  private sequence = 0
  private listeners = new Set<() => void>()

  constructor(private readonly run: (value: unknown, context: FlowNodeContext) => Promise<Record<string, ContextFeedValue>>) {}

  /** Subscribe to state changes (the inspector re-renders on notify). */
  onChange(listener: () => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  /** The source input's value changed: drop the old feeds, mark candidates stale. */
  invalidate(): void {
    this.sequence++
    this.state.feeds = {}
    this.state.error = null
    this.state.stale = true
    this.state.running = false
    this.notify()
  }

  reset(): void {
    this.sequence++
    this.state.feeds = {}
    this.state.error = null
    this.state.stale = false
    this.state.running = false
    this.notify()
  }

  /** Runs one analysis; a response from anything but the latest sequence is dropped.
   *  The triggering input's own context declaration is passed through — a node may
   *  declare several context inputs and each must run its own contract. */
  async start(value: unknown, context: FlowNodeContext): Promise<void> {
    const sequence = ++this.sequence
    this.state.running = true
    this.state.error = null
    this.notify()
    try {
      const feeds = await this.run(value, context)
      if (sequence !== this.sequence) return
      this.state.feeds = feeds
      this.state.stale = false
    } catch (e) {
      if (sequence !== this.sequence) return
      this.state.feeds = {}
      this.state.error = e instanceof Error ? e.message : String(e)
    } finally {
      if (sequence === this.sequence) this.state.running = false
      this.notify()
    }
  }

  private notify(): void {
    for (const listener of this.listeners) listener()
  }
}
