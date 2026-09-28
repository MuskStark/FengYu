/**
 * Client-side i18n for the Markdown Editor UI. The host pushes the active locale through
 * `environment` events; this module ships the `mde.*` message map for en/zh and picks the matching
 * table. Mirrors the offlinepython frontend i18n shape (flat keys + messagesFor/format).
 *
 * Both tables MUST keep identical key sets so neither locale ever renders a raw key.
 */
import { createFengYuI18n } from '@infinia/plugin-ui'

export type Messages = Record<string, string>

// The five original Vue-era keys are kept verbatim; the mde.* table grew with the
// T1 写作台 shell (nav rail, doc rail, format dock, status bar).
const en: Messages = {
  'mde.cardTitle': 'Markdown',
  'mde.editor': 'Markdown',
  'mde.preview': 'Preview',
  'mde.rendering': 'Rendering preview…',
  'mde.renderFailed': 'Render failed',
  'mde.navDocs': 'Documents',
  'mde.navFavorites': 'Favorites',
  'mde.navExport': 'Export',
  'mde.navPermissions': 'Permissions',
  'mde.navSettings': 'Settings',
  'mde.searchDocs': 'Search documents…',
  'mde.searchPath': 'Filter by path…',
  'mde.searchRecent': 'Recently opened…',
  'mde.noResults': 'No matching documents',
  'mde.docSynced': 'Synced',
  'mde.docDraft': 'Draft',
  'mde.docLocal': 'Local',
  'mde.workerReady': 'Worker ready',
  'mde.workerOffline': 'No host · raw source',
  'mde.renderMs': 'Render {0} ms',
  'mde.wordCount': 'Words {0}',
  'mde.placeholderMessage': 'Nothing here yet — the editor lives under “{0}”.',
  'mde.sampleText': 'text',
  'mde.bold': 'Bold',
  'mde.italic': 'Italic',
  'mde.heading2': 'Heading 2',
  'mde.list': 'List',
  'mde.quote': 'Quote',
  'mde.code': 'Inline code',
  'mde.link': 'Link',
  'mde.table': 'Table',
}

const zh: Messages = {
  'mde.cardTitle': 'Markdown',
  'mde.editor': 'Markdown',
  'mde.preview': '预览',
  'mde.rendering': '正在渲染预览…',
  'mde.renderFailed': '渲染失败',
  'mde.navDocs': '文档',
  'mde.navFavorites': '收藏',
  'mde.navExport': '导出',
  'mde.navPermissions': '权限',
  'mde.navSettings': '设置',
  'mde.searchDocs': '搜索文档…',
  'mde.searchPath': '按路径过滤…',
  'mde.searchRecent': '最近打开…',
  'mde.noResults': '没有匹配的文档',
  'mde.docSynced': '已同步',
  'mde.docDraft': '草稿',
  'mde.docLocal': '本地',
  'mde.workerReady': '工作进程 就绪',
  'mde.workerOffline': '无宿主 · 原始源码',
  'mde.renderMs': '渲染 {0} ms',
  'mde.wordCount': '字数 {0}',
  'mde.placeholderMessage': '这里还什么都没有——编辑器在「{0}」下。',
  'mde.sampleText': '文本',
  'mde.bold': '加粗',
  'mde.italic': '斜体',
  'mde.heading2': '二级标题',
  'mde.list': '列表',
  'mde.quote': '引用',
  'mde.code': '行内代码',
  'mde.link': '链接',
  'mde.table': '表格',
}

export const tables: Record<string, Messages> = { en, zh }
export const pluginI18n = createFengYuI18n(tables)

/** Resolve the active message table from a locale string (defaults to en). */
export function messagesFor(locale: string | undefined): Messages {
  if (!locale) return en
  return tables[locale.toLowerCase().startsWith('zh') ? 'zh' : 'en'] ?? en
}

/** Look up a key with positional {0}/{1}/… substitution. Falls back to the key itself. */
export function format(messages: Messages, key: string, ...args: (string | number)[]): string {
  let out = messages[key] ?? key
  args.forEach((a, i) => { out = out.replaceAll(`{${i}}`, String(a)) })
  return out
}
