/**
 * Site navigation — the single source of truth for BOTH the content pipeline
 * (scripts/build-content.mjs derives the published route set from these links)
 * and the React shell (src/content/navigation.ts re-exports it typed).
 *
 * Every link resolves to a committed page in docs/{en,zh}; if you add a link
 * here, ship the page too — the build fails on a nav link with no file, and
 * pages on disk that no link reaches are not published. The Changelog nav
 * entry targets docs/{en,zh}/reference/changelog.md, which is regenerated
 * from the root CHANGELOG.md by docs/scripts/sync-changelog.mjs on every
 * dev/build.
 */

export const en = {
  nav: [
    { text: 'Quickstart', link: '/en/quickstart' },
    { text: 'Architecture', link: '/en/architecture/overview' },
    { text: 'Plugins', link: '/en/plugins/overview' },
    { text: 'Guide', link: '/en/guide/ai-chat' },
    { text: 'Reference', link: '/en/reference/rest-api' },
    { text: 'Changelog', link: '/en/reference/changelog' }
  ],
  sidebar: [
    { text: 'Start', items: [
      { text: 'Quick Start', link: '/en/quickstart' },
      { text: 'Features', link: '/en/features' },
      { text: 'Design System', link: '/en/design-system' }
    ]},
    { text: 'Architecture', items: [
      { text: 'Overview', link: '/en/architecture/overview' },
      { text: 'Backend', link: '/en/architecture/backend' },
      { text: 'Frontend', link: '/en/architecture/frontend' },
      { text: 'Desktop', link: '/en/architecture/desktop' },
      { text: 'Plugin System', link: '/en/architecture/plugin-system' }
    ]},
    { text: 'Plugins', items: [
      { text: 'Overview', link: '/en/plugins/overview' },
      { text: 'Getting Started', link: '/en/plugins/getting-started' },
      { text: 'Manifest', link: '/en/plugins/manifest' },
      { text: 'Worker (JSON-RPC)', link: '/en/plugins/worker' },
      { text: 'UI Micro-frontend', link: '/en/plugins/ui-microfrontend' },
      { text: 'UI Components', link: '/en/plugins/ui-components' },
      { text: 'Aceternity UI Components', link: '/en/plugins/aceternity-ui' },
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
      { text: 'Built-in: Browser Capability', link: '/en/plugins/official-browser' },
      { text: 'Pitfalls', link: '/en/plugins/pitfalls' }
    ]},
    { text: 'Guide', items: [
      { text: 'AI Chat', link: '/en/guide/ai-chat' },
      { text: 'AI Agent', link: '/en/guide/ai-agent' },
      { text: 'MCP Apps', link: '/en/guide/mcp-apps' },
      { text: 'Flow Nodes', link: '/en/guide/flow-nodes' },
      { text: 'Skills', link: '/en/skills' },
      { text: 'Database', link: '/en/guide/database' },
      { text: 'Configuration', link: '/en/guide/configuration' }
    ]},
    { text: 'Reference', items: [
      { text: 'REST API', link: '/en/reference/rest-api' },
      { text: 'SSE Events', link: '/en/reference/sse-events' },
      { text: 'Troubleshooting', link: '/en/reference/troubleshooting' },
      { text: 'Glossary', link: '/en/reference/glossary' },
      { text: 'Changelog', link: '/en/reference/changelog' }
    ]}
  ]
}

export const zh = {
  nav: [
    { text: '快速开始', link: '/zh/quickstart' },
    { text: '架构', link: '/zh/architecture/overview' },
    { text: '插件', link: '/zh/plugins/overview' },
    { text: '指南', link: '/zh/guide/ai-chat' },
    { text: '参考', link: '/zh/reference/rest-api' },
    { text: '更新日志', link: '/zh/reference/changelog' }
  ],
  sidebar: [
    { text: '开始', items: [
      { text: '快速开始', link: '/zh/quickstart' },
      { text: '功能特性', link: '/zh/features' },
      { text: '设计系统', link: '/zh/design-system' }
    ]},
    { text: '架构', items: [
      { text: '概述', link: '/zh/architecture/overview' },
      { text: '后端', link: '/zh/architecture/backend' },
      { text: '前端', link: '/zh/architecture/frontend' },
      { text: '桌面端', link: '/zh/architecture/desktop' },
      { text: '插件系统', link: '/zh/architecture/plugin-system' }
    ]},
    { text: '插件', items: [
      { text: '概述', link: '/zh/plugins/overview' },
      { text: '入门', link: '/zh/plugins/getting-started' },
      { text: '清单', link: '/zh/plugins/manifest' },
      { text: 'Worker（JSON-RPC）', link: '/zh/plugins/worker' },
      { text: 'UI 微前端', link: '/zh/plugins/ui-microfrontend' },
      { text: 'UI 组件', link: '/zh/plugins/ui-components' },
      { text: 'Aceternity UI 组件', link: '/zh/plugins/aceternity-ui' },
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
      { text: '内置能力：浏览器', link: '/zh/plugins/official-browser' },
      { text: '常见陷阱', link: '/zh/plugins/pitfalls' }
    ]},
    { text: '指南', items: [
      { text: 'AI 对话', link: '/zh/guide/ai-chat' },
      { text: 'AI 智能体', link: '/zh/guide/ai-agent' },
      { text: 'MCP Apps', link: '/zh/guide/mcp-apps' },
      { text: '流程节点', link: '/zh/guide/flow-nodes' },
      { text: '技能', link: '/zh/skills' },
      { text: '数据库', link: '/zh/guide/database' },
      { text: '配置', link: '/zh/guide/configuration' }
    ]},
    { text: '参考', items: [
      { text: 'REST API', link: '/zh/reference/rest-api' },
      { text: 'SSE 事件', link: '/zh/reference/sse-events' },
      { text: '故障排查', link: '/zh/reference/troubleshooting' },
      { text: '术语表', link: '/zh/reference/glossary' },
      { text: '更新日志', link: '/zh/reference/changelog' }
    ]}
  ]
}
