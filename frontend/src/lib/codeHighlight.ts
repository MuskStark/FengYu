/**
 * shiki-backed source highlighting for the workspace file preview.
 *
 * Engine choice: oniguruma-WASM (same call ZCode makes under @pierre/diffs —
 * their notes flag the pure-JS regex engine's TextMate regexes as a V8
 * code-cage hazard in long-lived renderer processes, which this desktop
 * webview also is).
 *
 * Bundle shape: the fine-grained `shiki/core` assembly (NOT the convenience
 * `shiki` full bundle, which drags every bundled grammar into the build as
 * dead chunks). Only the grammars listed below ship, and the whole module is
 * behind dynamic imports so nothing loads until a preview actually opens.
 *
 * Rendering model: one HTML string per line (`<span class="fv-t" style="…">`),
 * with BOTH theme palettes baked as CSS custom properties (`--shiki-light` /
 * `--shiki-dark`, the canonical shiki dual-theme recipe). Switching the app
 * theme is then a pure CSS flip in file-viewer.css — no re-tokenization.
 */
import type {
  HighlighterCore, LanguageRegistration, MaybeArray, ThemedTokenWithVariants,
} from 'shiki/core'
import { EXT_SHIKI_LANGS, escapeHtml, shikiLanguageFor } from './filePreview'

const THEME_LIGHT = 'github-light'
const THEME_DARK = 'github-dark'

/** shiki grammar id → its fine-grained module (the EXT_SHIKI_LANGS value set). */
const LANG_IMPORTS: Record<string, () => Promise<{ default: MaybeArray<LanguageRegistration> }>> = {
  typescript: () => import('@shikijs/langs/typescript'),
  tsx: () => import('@shikijs/langs/tsx'),
  javascript: () => import('@shikijs/langs/javascript'),
  jsx: () => import('@shikijs/langs/jsx'),
  json: () => import('@shikijs/langs/json'),
  java: () => import('@shikijs/langs/java'),
  python: () => import('@shikijs/langs/python'),
  markdown: () => import('@shikijs/langs/markdown'),
  css: () => import('@shikijs/langs/css'),
  scss: () => import('@shikijs/langs/scss'),
  less: () => import('@shikijs/langs/less'),
  html: () => import('@shikijs/langs/html'),
  xml: () => import('@shikijs/langs/xml'),
  vue: () => import('@shikijs/langs/vue'),
  yaml: () => import('@shikijs/langs/yaml'),
  bash: () => import('@shikijs/langs/bash'),
  sql: () => import('@shikijs/langs/sql'),
  go: () => import('@shikijs/langs/go'),
  rust: () => import('@shikijs/langs/rust'),
  c: () => import('@shikijs/langs/c'),
  cpp: () => import('@shikijs/langs/cpp'),
  csharp: () => import('@shikijs/langs/csharp'),
  php: () => import('@shikijs/langs/php'),
  ruby: () => import('@shikijs/langs/ruby'),
  kotlin: () => import('@shikijs/langs/kotlin'),
  swift: () => import('@shikijs/langs/swift'),
  scala: () => import('@shikijs/langs/scala'),
  ini: () => import('@shikijs/langs/ini'),
  toml: () => import('@shikijs/langs/toml'),
  properties: () => import('@shikijs/langs/properties'),
  diff: () => import('@shikijs/langs/diff'),
  dockerfile: () => import('@shikijs/langs/dockerfile'),
  makefile: () => import('@shikijs/langs/makefile'),
  graphql: () => import('@shikijs/langs/graphql'),
  proto: () => import('@shikijs/langs/proto'),
}

/**
 * Past this size the highlighter is skipped and the file renders as plain
 * escaped lines — tokenizing megabytes would stall the pane for no real gain.
 */
export const MAX_HIGHLIGHT_BYTES = 512 * 1024

let highlighterPromise: Promise<HighlighterCore> | null = null

function getHighlighter(): Promise<HighlighterCore> {
  highlighterPromise ??= (async () => {
    const { createHighlighterCore } = await import('shiki/core')
    const { createOnigurumaEngine } = await import('shiki/engine/oniguruma')
    return createHighlighterCore({
      themes: [
        import('@shikijs/themes/github-light'),
        import('@shikijs/themes/github-dark'),
      ],
      langs: [...new Set(Object.values(EXT_SHIKI_LANGS))].map(lang => LANG_IMPORTS[lang]!()),
      engine: createOnigurumaEngine(import('shiki/wasm')),
    })
  })()
  return highlighterPromise
}

/**
 * Highlight file content into one HTML string per line, or null when the path's
 * language is unknown (caller renders plain escaped lines) or the highlighter
 * is unavailable/over budget (same plain fallback). Never throws.
 */
export async function highlightLines(path: string, content: string): Promise<string[] | null> {
  const language = shikiLanguageFor(path)
  if (!language || content.length > MAX_HIGHLIGHT_BYTES) return null
  try {
    const highlighter = await getHighlighter()
    const lines = highlighter.codeToTokensWithThemes(content, {
      lang: language,
      themes: { light: THEME_LIGHT, dark: THEME_DARK },
    })
    return lines.map(line => line.map(tokenToSpan).join('') || '&#8203;')
  } catch {
    // A failed engine load must never break reading the file — plain text still shows.
    return null
  }
}

function tokenToSpan(token: ThemedTokenWithVariants): string {
  const light = token.variants.light
  const dark = token.variants.dark
  const declarations: string[] = []
  if (light?.color || dark?.color) {
    declarations.push(`--shiki-light:${light?.color ?? 'inherit'}`)
    declarations.push(`--shiki-dark:${dark?.color ?? 'inherit'}`)
  }
  if (light?.bgColor || dark?.bgColor) {
    declarations.push(`--shiki-light-bg:${light?.bgColor ?? 'inherit'}`)
    declarations.push(`--shiki-dark-bg:${dark?.bgColor ?? 'inherit'}`)
  }
  // Style flags are effectively theme-invariant across the github pair; read from light.
  const flags = light?.fontStyle
  if (flags) {
    if (flags & 1) declarations.push('font-style:italic')
    if (flags & 2) declarations.push('font-weight:bold')
    if (flags & 4) declarations.push('text-decoration:underline')
    if (flags & 8) declarations.push('text-decoration:line-through')
  }
  const style = declarations.join(';')
  return style
    ? `<span class="fv-t" style="${style}">${escapeHtml(token.content)}</span>`
    : `<span class="fv-t">${escapeHtml(token.content)}</span>`
}
