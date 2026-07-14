import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'Infinia',
  description: 'Where bees go, flows follow. — A modular web + desktop toolbox.',
  lastUpdated: true,
  cleanUrls: true,
  sitemap: { hostname: 'https://muskstark.github.io/FengYu/' },
  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/logo.svg' }],
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

// NOTE: enNav/enSidebar/zhNav/zhSidebar are defined in Task 1 (root redirect) + Task 2.
// For the shell, define minimal placeholders so the site builds:
const enNav = [{ text: 'Home', link: '/en/' }]
const enSidebar = {}
const zhNav = [{ text: '首页', link: '/zh/' }]
const zhSidebar = {}
