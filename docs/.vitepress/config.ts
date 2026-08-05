import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'Infinia',
  description: 'Where bees go, flows follow. — A modular web + desktop toolbox.',
  // The docs site is served from a sub-path (muskstark.github.io/FengYu/),
  // not the domain root, so every internal link/asset must be prefixed with it.
  base: '/FengYu/',
  lastUpdated: true,
  cleanUrls: true,
  srcExclude: ['superpowers/**'],
  // Dead-link checking is intentionally left enabled (no `ignoreDeadLinks`):
  // all EN + ZH pages are complete, so the build must fail loudly on any
  // broken internal link rather than silently swallowing it.
  sitemap: { hostname: 'https://muskstark.github.io/FengYu/' },
  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/FengYu/logo.svg' }],
    ['meta', { name: 'theme-color', content: '#6750A4' }]
  ],
  locales: {
    root: { label: 'English', lang: 'en', link: '/en/', themeConfig: { nav: enNav, sidebar: enSidebar } },
    zh: { label: '简体中文', lang: 'zh-CN', link: '/zh/', themeConfig: { nav: zhNav, sidebar: zhSidebar } }
  },
  themeConfig: {
    logo: '/logo.svg',
    socialLinks: [{ icon: 'github', link: 'https://github.com/MuskStark/FengYu' }],
    search: { provider: 'local' },
    footer: {
      message: 'Released under the GPL-3.0 License.',
      copyright: 'Copyright © 2026 Infinia · 蜂语 FengYu'
    }
  }
})

// Nav + sidebar for both locales. Every link below resolves to a committed page
// in docs/{en,zh}; if you add a link here, ship the page too. The Changelog nav
// entry targets docs/{en,zh}/reference/changelog.md, which is regenerated from
// the root CHANGELOG.md by docs/scripts/sync-changelog.mjs on every dev/build.

const enNav = [
  { text: 'Quickstart', link: '/en/quickstart' },
  { text: 'Architecture', link: '/en/architecture/overview' },
  { text: 'Plugins', link: '/en/plugins/overview' },
  { text: 'Guide', link: '/en/guide/ai-chat' },
  { text: 'Reference', link: '/en/reference/rest-api' },
  { text: 'Changelog', link: '/en/reference/changelog' }
]

const enSidebar = {
  '/en/': [
    { text: 'Start', items: [
      { text: 'Home', link: '/en/' },
      { text: 'Quick Start', link: '/en/quickstart' },
      { text: 'Features', link: '/en/features' },
      { text: 'Design System', link: '/en/design-system' }
    ]},
    { text: 'Architecture', collapsible: true, items: [
      { text: 'Overview', link: '/en/architecture/overview' },
      { text: 'Backend', link: '/en/architecture/backend' },
      { text: 'Frontend', link: '/en/architecture/frontend' },
      { text: 'Desktop', link: '/en/architecture/desktop' },
      { text: 'Plugin System', link: '/en/architecture/plugin-system' }
    ]},
    { text: 'Plugins', collapsible: true, items: [
      { text: 'Overview', link: '/en/plugins/overview' },
      { text: 'Getting Started', link: '/en/plugins/getting-started' },
      { text: 'Manifest', link: '/en/plugins/manifest' },
      { text: 'Worker (JSON-RPC)', link: '/en/plugins/worker' },
      { text: 'UI Micro-frontend', link: '/en/plugins/ui-microfrontend' },
      { text: 'UI Components', link: '/en/plugins/ui-components' },
      { text: 'File I/O', link: '/en/plugins/file-io' },
      { text: 'Database Standard', link: '/en/plugins/database' },
      { text: 'AI Tools', link: '/en/plugins/ai-tools' },
      { text: 'SDK & CLI', link: '/en/plugins/sdk-cli' },
      { text: 'Marketplace', link: '/en/plugins/marketplace' },
      { text: 'i18n', link: '/en/plugins/i18n' },
      { text: 'Build & Deploy', link: '/en/plugins/build-deploy' },
      { text: 'Official: Markdown', link: '/en/plugins/official-markdown' },
      { text: 'Official: Excel', link: '/en/plugins/official-excel' },
      { text: 'Official: Email Center', link: '/en/plugins/email-center' },
      { text: 'Official: Offline Python', link: '/en/plugins/official-offlinepython' },
      { text: 'Official: Browser Agent', link: '/en/plugins/official-browser' },
      { text: 'Pitfalls', link: '/en/plugins/pitfalls' }
    ]},
    { text: 'Guide', collapsible: true, items: [
      { text: 'AI Chat', link: '/en/guide/ai-chat' },
      { text: 'AI Agent', link: '/en/guide/ai-agent' },
      { text: 'Skills', link: '/en/skills/' },
      { text: 'Database', link: '/en/guide/database' },
      { text: 'Configuration', link: '/en/guide/configuration' }
    ]},
    { text: 'Reference', collapsible: true, items: [
      { text: 'REST API', link: '/en/reference/rest-api' },
      { text: 'SSE Events', link: '/en/reference/sse-events' },
      { text: 'Troubleshooting', link: '/en/reference/troubleshooting' },
      { text: 'Glossary', link: '/en/reference/glossary' },
      { text: 'Changelog', link: '/en/reference/changelog' }
    ]}
  ]
}

const zhNav = [
  { text: '快速开始', link: '/zh/quickstart' },
  { text: '架构', link: '/zh/architecture/overview' },
  { text: '插件', link: '/zh/plugins/overview' },
  { text: '指南', link: '/zh/guide/ai-chat' },
  { text: '参考', link: '/zh/reference/rest-api' },
  { text: '更新日志', link: '/zh/reference/changelog' }
]

const zhSidebar = {
  '/zh/': [
    { text: '开始', items: [
      { text: '首页', link: '/zh/' },
      { text: '快速开始', link: '/zh/quickstart' },
      { text: '功能特性', link: '/zh/features' },
      { text: '设计系统', link: '/zh/design-system' }
    ]},
    { text: '架构', collapsible: true, items: [
      { text: '概述', link: '/zh/architecture/overview' },
      { text: '后端', link: '/zh/architecture/backend' },
      { text: '前端', link: '/zh/architecture/frontend' },
      { text: '桌面端', link: '/zh/architecture/desktop' },
      { text: '插件系统', link: '/zh/architecture/plugin-system' }
    ]},
    { text: '插件', collapsible: true, items: [
      { text: '概述', link: '/zh/plugins/overview' },
      { text: '入门', link: '/zh/plugins/getting-started' },
      { text: '清单', link: '/zh/plugins/manifest' },
      { text: 'Worker（JSON-RPC）', link: '/zh/plugins/worker' },
      { text: 'UI 微前端', link: '/zh/plugins/ui-microfrontend' },
      { text: 'UI 组件', link: '/zh/plugins/ui-components' },
      { text: '文件 I/O', link: '/zh/plugins/file-io' },
      { text: '数据库规范', link: '/zh/plugins/database' },
      { text: 'AI 工具', link: '/zh/plugins/ai-tools' },
      { text: 'SDK 与 CLI', link: '/zh/plugins/sdk-cli' },
      { text: '插件市场', link: '/zh/plugins/marketplace' },
      { text: '国际化', link: '/zh/plugins/i18n' },
      { text: '构建与部署', link: '/zh/plugins/build-deploy' },
      { text: '官方插件：Markdown', link: '/zh/plugins/official-markdown' },
      { text: '官方插件：Excel', link: '/zh/plugins/official-excel' },
      { text: '官方插件：邮件中心', link: '/zh/plugins/email-center' },
      { text: '官方插件：Offline Python', link: '/zh/plugins/official-offlinepython' },
      { text: '官方插件：浏览器代理', link: '/zh/plugins/official-browser' },
      { text: '常见陷阱', link: '/zh/plugins/pitfalls' }
    ]},
    { text: '指南', collapsible: true, items: [
      { text: 'AI 对话', link: '/zh/guide/ai-chat' },
      { text: 'AI 智能体', link: '/zh/guide/ai-agent' },
      { text: '技能', link: '/zh/skills/' },
      { text: '数据库', link: '/zh/guide/database' },
      { text: '配置', link: '/zh/guide/configuration' }
    ]},
    { text: '参考', collapsible: true, items: [
      { text: 'REST API', link: '/zh/reference/rest-api' },
      { text: 'SSE 事件', link: '/zh/reference/sse-events' },
      { text: '故障排查', link: '/zh/reference/troubleshooting' },
      { text: '术语表', link: '/zh/reference/glossary' },
      { text: '更新日志', link: '/zh/reference/changelog' }
    ]}
  ]
}
