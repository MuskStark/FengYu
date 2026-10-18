import type { Locale } from './content/navigation'

/**
 * Chrome strings for the two locales. Page CONTENT is file-tree i18n
 * (docs/en + docs/zh); only the shell's fixed furniture lives here — small
 * enough that a dictionary beats an i18n framework for a docs-only site.
 */
const strings = {
  en: {
    search: 'Search docs',
    searchPlaceholder: 'Search documentation…',
    searchNoResults: 'No results',
    searchHint: 'to navigate · to select · to close',
    onThisPage: 'On this page',
    outline: 'Outline',
    lastUpdated: 'Last updated',
    editOnGitHub: 'Edit on GitHub',
    previous: 'Previous',
    next: 'Next',
    home: 'Home',
    skipToContent: 'Skip to content',
    copy: 'Copy',
    copied: 'Copied',
    notFoundTitle: '404 — page not found',
    notFoundBody: 'The page you are looking for does not exist or has been moved.',
    backHome: 'Back to the docs home',
    openMenu: 'Open navigation',
    closeMenu: 'Close navigation',
    toggleTheme: 'Toggle color theme',
    switchLang: '切换到中文',
    language: 'Language',
    github: 'GitHub repository',
    latest: 'Latest',
    footerLicense: 'Released under the GPL-3.0 License.',
    footerCopyright: 'Copyright © 2026 Infinia · 蜂语 FengYu',
    explore: 'Explore',
  },
  zh: {
    search: '搜索文档',
    searchPlaceholder: '搜索文档…',
    searchNoResults: '没有找到结果',
    searchHint: '选择 · 打开 · 关闭',
    onThisPage: '本页目录',
    outline: '目录',
    lastUpdated: '最后更新',
    editOnGitHub: '在 GitHub 上编辑',
    previous: '上一篇',
    next: '下一篇',
    home: '首页',
    skipToContent: '跳到正文',
    copy: '复制',
    copied: '已复制',
    notFoundTitle: '404 — 页面不存在',
    notFoundBody: '你访问的页面不存在，或已被移动。',
    backHome: '返回文档首页',
    openMenu: '打开导航',
    closeMenu: '关闭导航',
    toggleTheme: '切换颜色主题',
    switchLang: 'Switch to English',
    language: 'Language',
    github: 'GitHub 仓库',
    latest: '最新',
    footerLicense: '基于 GPL-3.0 许可发布。',
    footerCopyright: 'Copyright © 2026 Infinia · 蜂语 FengYu',
    explore: '查看',
  },
} as const

/** Widened shape of one locale's catalog (literal types relaxed for the zh twin). */
export type Strings = { [K in keyof (typeof strings)['en']]: string }

export function t(locale: Locale): Strings {
  return strings[locale]
}
