// 阶段文案词表。注意：resources/splash.html 内 <script> 中有一份内容相同的
// MESSAGES/BRAND 镜像（splash HTML 无法 import TS）。修改本文件时必须同步那边。

export type SplashStage = 'spawning' | 'port-ready' | 'health-ready' | 'loading-ui'
export type SplashLocale = 'zh' | 'en'

export const MESSAGES: Record<SplashLocale, Record<SplashStage, string>> = {
  zh: {
    'spawning': '正在启动蜂语…',
    'port-ready': '正在初始化服务…',
    'health-ready': '正在加载工作区…',
    'loading-ui': '即将就绪',
  },
  en: {
    'spawning': 'Starting FengYu…',
    'port-ready': 'Initializing service…',
    'health-ready': 'Loading workspace…',
    'loading-ui': 'Almost ready',
  },
}

export const BRAND: Record<SplashLocale, { name: string; sub: string }> = {
  zh: { name: '蜂语', sub: 'Infinia · FengYu' },
  en: { name: 'Infinia', sub: 'FengYu · 蜂语' },
}

/**
 * Resolve a BCP-47 locale string (e.g. app.getLocale() → "zh-CN", "en-US") to a
 * splash locale. Any non-zh tag falls back to English.
 */
export function pickLocale(raw: string): SplashLocale {
  const tag = (raw || '').toLowerCase().split('-')[0]
  return tag === 'zh' ? 'zh' : 'en'
}
