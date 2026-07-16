/**
 * Client-side i18n for the Offline Python Builder UI. The host pushes the active locale
 * through `bindFengYuEnvironment`; this module ships the `opb.*` message map for en/zh and
 * picks the matching table. Mirrors the backend `i18n/messages[_zh].properties` keys.
 */
export type Messages = Record<string, string>

const en: Messages = {
  'opb.title': 'Offline Python Builder',
  'opb.nav.config': 'Config',
  'opb.nav.build': 'Build & Verify',
  'opb.nav.deploy': 'Deploy',
  'opb.nav.doctor': 'Doctor',
  'opb.project.empty': 'No project open',
  'opb.project.open': 'Open Project',
  'opb.project.new': 'New Project',
  'opb.config.title': 'Configuration',
  'opb.config.requirements': 'requirements.txt',
  'opb.config.pythonVersion': 'Python version',
  'opb.config.platforms': 'Target platforms',
  'opb.config.download': 'Download options',
  'opb.config.onlyBinary': 'Only binary (wheels)',
  'opb.config.recursive': 'Resolve dependencies recursively',
  'opb.config.save': 'Save config',
  'opb.build.title': 'Build & Verify',
  'opb.build.start': 'Build repository',
  'opb.build.cancel': 'Cancel',
  'opb.build.verify': 'Verify',
  'opb.build.package': 'Package bundle',
  'opb.deploy.title': 'Offline Deploy',
  'opb.deploy.selectZip': 'Select bundle ZIP',
  'opb.deploy.targetGlobal': 'Global environment',
  'opb.deploy.targetVenv': 'New virtual environment',
  'opb.deploy.venvPath': 'venv path',
  'opb.deploy.start': 'Start install',
  'opb.doctor.title': 'Environment Doctor',
  'opb.doctor.refresh': 'Re-check',
  'opb.python.detected': 'Python {0} · pip {1}',
  'opb.python.missing': 'Python not detected (>=3.10 required)',
}

const zh: Messages = {
  'opb.title': '离线 Python 构建器',
  'opb.nav.config': '配置',
  'opb.nav.build': '构建 & 校验',
  'opb.nav.deploy': '部署',
  'opb.nav.doctor': '环境诊断',
  'opb.project.empty': '未打开项目',
  'opb.project.open': '打开项目',
  'opb.project.new': '新建项目',
  'opb.config.title': '配置',
  'opb.config.requirements': 'requirements.txt',
  'opb.config.pythonVersion': 'Python 版本',
  'opb.config.platforms': '目标平台',
  'opb.config.download': '下载选项',
  'opb.config.onlyBinary': '仅二进制(wheels)',
  'opb.config.recursive': '递归解析依赖',
  'opb.config.save': '保存配置',
  'opb.build.title': '构建 & 校验',
  'opb.build.start': '构建仓库',
  'opb.build.cancel': '取消',
  'opb.build.verify': '校验',
  'opb.build.package': '打包 bundle',
  'opb.deploy.title': '离线部署',
  'opb.deploy.selectZip': '选择 bundle ZIP',
  'opb.deploy.targetGlobal': '全局环境',
  'opb.deploy.targetVenv': '新建虚拟环境',
  'opb.deploy.venvPath': 'venv 路径',
  'opb.deploy.start': '开始安装',
  'opb.doctor.title': '环境诊断',
  'opb.doctor.refresh': '重新检测',
  'opb.python.detected': 'Python {0} · pip {1}',
  'opb.python.missing': '未检测到 Python(需要 >=3.10)',
}

const tables: Record<string, Messages> = { en, zh }

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
