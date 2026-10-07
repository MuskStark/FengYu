/**
 * Provider-model picker helpers: curated fallbacks + row building for the chat
 * composer's model menu. Live vendor listings are the primary source
 * (`GET /api/ai/providers/{id}/models`); these kick in only when the vendor is
 * unreachable, so a provider group is never a single dead row.
 */

/**
 * Curated per-BUILTIN-provider suggestions — a short "known good" list per the
 * bundled model catalog, shown only when the live listing failed. Custom
 * providers get no suggestions (we don't know their lineup) — manual entry
 * covers them.
 */
export const BUILTIN_MODEL_SUGGESTIONS: Record<string, string[]> = {
  openai: ['gpt-4o', 'gpt-4o-mini', 'gpt-4.1', 'gpt-4.1-mini', 'o3', 'o4-mini'],
  anthropic: ['claude-sonnet-4-20250514', 'claude-opus-4-20250514',
    'claude-3-7-sonnet-20250219', 'claude-3-5-haiku-20241022'],
  deepseek: ['deepseek-chat', 'deepseek-reasoner', 'deepseek-v4-flash'],
  ollama: ['qwen3:4b', 'qwen3:8b', 'qwen3:14b', 'llama3.2:3b', 'gemma3:4b', 'deepseek-r1:8b'],
}

/**
 * Rows a provider group shows: the live vendor list when it answered; else the
 * configured model first, followed by curated suggestions (deduped, blanks
 * dropped). `fallback` reports whether curated rows are shown, so the menu can
 * label them as suggestions rather than a live listing.
 */
export function providerModelRows(
  live: string[],
  configured: string | undefined,
  providerId: string,
): { rows: string[]; fallback: boolean } {
  if (live.length) return { rows: live, fallback: false }
  const curated = BUILTIN_MODEL_SUGGESTIONS[providerId] ?? []
  const rows = [...new Set([configured, ...curated]
    .filter((m): m is string => !!m?.trim()))]
  return { rows, fallback: curated.length > 0 }
}
