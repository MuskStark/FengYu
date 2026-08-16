/**
 * Client-side i18n for the Excel Splitter UI. The host pushes the active locale through
 * `environment` events; this module ships the `exui.*` message map for en/zh and picks the matching
 * table. (Frontend keys use the `exui.` prefix to stay distinct from the backend worker's `ex.*`
 * keys.) Mirrors the offlinepython/markdown frontend i18n shape (flat keys + messagesFor/format).
 *
 * Both tables MUST keep identical key sets so neither locale ever renders a raw key.
 */
import { createFengYuI18n } from '@infinia/plugin-ui'

export type Messages = Record<string, string>

const en: Messages = {
  'exui.title': 'Excel Splitter',
  // Wizard steps
  'exui.step.source': 'Source',
  'exui.step.mode': 'Mode',
  'exui.step.output': 'Output',
  'exui.step.run': 'Run',
  // Wizard nav buttons
  'exui.wizard.back': 'Back',
  'exui.wizard.next': 'Next',
  'exui.wizard.finish': 'Run split',
  'exui.wizard.retry': 'Retry',
  'exui.wizard.optional': 'optional',
  // Source step
  'exui.source.ariaSplitMode': 'Split mode',
  'exui.source.cardTitle': 'Choose data source',
  'exui.source.zoneTitle': 'Select an Excel file',
  'exui.source.zoneSub': 'Supports .xlsx / .xls files',
  'exui.source.browse': 'Browse…',
  'exui.source.tipsTitle': 'Your source file stays untouched',
  'exui.source.tip1': 'Only the workbook structure (sheets and columns) is read',
  'exui.source.tip2': 'Results go to the folder you choose — the source stays as-is',
  'exui.source.tip3': 'Large workbooks are fine: analysis and splitting run in a separate process',
  'exui.source.analyzing': 'Analyzing…',
  // Mode step — cards + per-mode note
  'exui.mode.cardTitle': 'Choose split mode',
  'exui.mode.bySheet.label': 'By sheet',
  'exui.mode.bySheet.hint': 'One file per sheet',
  'exui.mode.byColumn.label': 'By column',
  'exui.mode.byColumn.hint': 'One file per value in a column',
  'exui.mode.complex.label': 'Complex',
  'exui.mode.complex.hint': 'Multiple rules combined',
  'exui.mode.noteColumn': 'Each distinct value in the selected column becomes its own output file',
  'exui.mode.noteComplex': 'Each rule produces one output file; tick “Copy entire sheet” to copy a sheet as-is',
  // Mode step — BY_SHEET / BY_COLUMN
  'exui.mode.sheets': 'Sheets (leave empty for all)',
  'exui.mode.sheet': 'Sheet',
  'exui.mode.column': 'Column',
  // Mode step — COMPLEX table
  'exui.complex.sheet': 'Sheet',
  'exui.complex.headerRow': 'Header row',
  'exui.complex.column': 'Column',
  'exui.complex.copyEntire': 'Copy entire sheet',
  'exui.complex.remove': 'Remove',
  'exui.complex.addRule': 'Add rule',
  'exui.mode.filePrefix': 'Output file prefix (optional)',
  // Output step — config summary + directory panel
  'exui.output.cardTitle': 'Confirm output',
  'exui.output.configTitle': 'Split configuration',
  'exui.output.mode': 'Mode',
  'exui.output.rules': 'Rules',
  'exui.output.expectedFiles': 'Expected files',
  'exui.output.estimating': 'estimating…',
  'exui.output.prefixLabel': 'File name prefix',
  'exui.output.dirPanel': 'Output folder',
  // modeLabel / configDetails composed strings
  'exui.modeLabel.bySheetAll': 'By sheet (all)',
  'exui.modeLabel.bySheetSelected': 'By sheet ({0} selected)',
  'exui.modeLabel.byColumnPlain': 'By column',
  'exui.modeLabel.byColumn': 'By column: {0} in {1}',
  'exui.modeLabel.complex': 'Complex ({0} rule)',
  'exui.modeLabel.complexPlural': 'Complex ({0} rules)',
  'exui.detail.columnInSheet': 'Column “{0}” in sheet “{1}”',
  'exui.detail.copyEntireSheet': 'Copy entire sheet “{0}”',
  'exui.detail.splitSheetByColumn': 'Split sheet “{0}” by column {1} (header row {2})',
  // Output step — picker + alerts
  'exui.output.chooseFolder': 'Choose output folder',
  'exui.output.desktopHint': 'Files are written directly into this folder — no download step needed.',
  'exui.output.webHint': 'Results are staged in a temporary folder; after the split you can download them as a zip.',
  // Run step
  'exui.run.splitting': 'Splitting…',
  'exui.run.starting': 'Starting…',
  'exui.run.detail': 'Splitting “{0}” ({1}) into “{2}”',
  // Complete step
  'exui.complete.title': 'Split complete',
  'exui.complete.written': '{0} file(s) written to {1}',
  'exui.complete.outputFolderFallback': 'the output folder',
  'exui.complete.outputFolderLabel': 'Output folder',
  'exui.complete.filesPanel': 'Output files',
  'exui.complete.actionsPanel': 'Next steps',
  'exui.complete.download': 'Download results (zip)',
  'exui.complete.restart': 'Split another file',
  // Validation messages (returned from validate* fns, surfaced via notify)
  'exui.validation.chooseExcelFile': 'Choose an Excel file',
  'exui.validation.chooseSheetAndColumn': 'Choose a sheet and column',
  'exui.validation.chooseSheetFromWorkbook': 'Choose a sheet from the analyzed workbook',
  'exui.validation.chooseColumnFromSheet': 'Choose a column from the analyzed sheet',
  'exui.validation.addOneRule': 'Add at least one complete split rule',
  'exui.validation.copyAllIndices': 'Copy-all rules require both indices to be -1',
  'exui.validation.positiveIndices': 'Use whole-number indices of 1 or greater',
  'exui.validation.chooseOutputFolder': 'Choose an output folder',
  'exui.validation.unknownStep': 'Unknown wizard step: {0}',
  // Response fallbacks
  'exui.fallback.analyzeFailed': 'Analyze failed',
  'exui.fallback.configureFailed': 'Configure failed',
  'exui.fallback.splitFailed': 'Split failed',
  // Persistence
  'exui.notify.unableSave': 'Unable to save wizard progress',
}

const zh: Messages = {
  'exui.title': 'Excel 拆分器',
  'exui.step.source': '来源',
  'exui.step.mode': '方式',
  'exui.step.output': '输出',
  'exui.step.run': '执行',
  'exui.wizard.back': '上一步',
  'exui.wizard.next': '下一步',
  'exui.wizard.finish': '执行拆分',
  'exui.wizard.retry': '重试',
  'exui.wizard.optional': '可选',
  'exui.source.ariaSplitMode': '拆分方式',
  'exui.source.cardTitle': '选择数据来源',
  'exui.source.zoneTitle': '选择 Excel 文件',
  'exui.source.zoneSub': '支持 .xlsx / .xls 文件',
  'exui.source.browse': '浏览…',
  'exui.source.tipsTitle': '拆分不会修改源文件',
  'exui.source.tip1': '系统只读取工作簿结构（工作表与列）',
  'exui.source.tip2': '输出写入你指定的目录，源文件保持原样',
  'exui.source.tip3': '大文件也可用：分析与拆分在独立进程完成',
  'exui.source.analyzing': '正在分析…',
  'exui.mode.bySheet.label': '按工作表',
  'exui.mode.bySheet.hint': '每个工作表一个文件',
  'exui.mode.byColumn.label': '按列',
  'exui.mode.byColumn.hint': '按某列的每个值拆分',
  'exui.mode.complex.label': '复杂',
  'exui.mode.complex.hint': '组合多条规则',
  'exui.mode.cardTitle': '选择拆分方式',
  'exui.mode.noteColumn': '将按所选列中的每个不同值，把工作表拆分为独立文件',
  'exui.mode.noteComplex': '每条规则生成一个输出文件；勾选「复制整表」则原样复制该工作表',
  'exui.mode.sheets': '工作表（留空表示全部）',
  'exui.mode.sheet': '工作表',
  'exui.mode.column': '列',
  'exui.complex.sheet': '工作表',
  'exui.complex.headerRow': '表头行',
  'exui.complex.column': '列',
  'exui.complex.copyEntire': '复制整张表',
  'exui.complex.remove': '移除',
  'exui.complex.addRule': '添加规则',
  'exui.mode.filePrefix': '输出文件名前缀（可选）',
  'exui.output.cardTitle': '确认输出',
  'exui.output.configTitle': '拆分配置',
  'exui.output.mode': '方式',
  'exui.output.rules': '规则',
  'exui.output.expectedFiles': '预期文件数',
  'exui.output.estimating': '估算中…',
  'exui.output.prefixLabel': '文件名前缀',
  'exui.output.dirPanel': '输出目录',
  'exui.modeLabel.bySheetAll': '按工作表（全部）',
  'exui.modeLabel.bySheetSelected': '按工作表（已选 {0} 个）',
  'exui.modeLabel.byColumnPlain': '按列',
  'exui.modeLabel.byColumn': '按列：{1} 中的 {0}',
  'exui.modeLabel.complex': '复杂（{0} 条规则）',
  'exui.modeLabel.complexPlural': '复杂（{0} 条规则）',
  'exui.detail.columnInSheet': '工作表「{1}」中的列「{0}」',
  'exui.detail.copyEntireSheet': '复制整张工作表「{0}」',
  'exui.detail.splitSheetByColumn': '按列 {1} 拆分工作表「{0}」（表头行 {2}）',
  'exui.output.chooseFolder': '选择输出目录',
  'exui.output.desktopHint': '文件将直接写入此目录，无需下载。',
  'exui.output.webHint': '结果先暂存在临时目录；拆分完成后可作为 zip 下载。',
  'exui.run.splitting': '正在拆分…',
  'exui.run.starting': '准备执行…',
  'exui.run.detail': '正在将「{0}」按「{1}」拆分并写入「{2}」',
  'exui.complete.title': '拆分完成',
  'exui.complete.written': '已写入 {0} 个文件到 {1}',
  'exui.complete.outputFolderFallback': '输出目录',
  'exui.complete.outputFolderLabel': '输出目录',
  'exui.complete.filesPanel': '输出文件',
  'exui.complete.actionsPanel': '后续操作',
  'exui.complete.download': '下载结果 (zip)',
  'exui.complete.restart': '再拆一个文件',
  'exui.validation.chooseExcelFile': '请选择 Excel 文件',
  'exui.validation.chooseSheetAndColumn': '请选择工作表和列',
  'exui.validation.chooseSheetFromWorkbook': '请从已分析的工作簿中选择工作表',
  'exui.validation.chooseColumnFromSheet': '请从已分析的工作表中选择列',
  'exui.validation.addOneRule': '请至少添加一条完整的拆分规则',
  'exui.validation.copyAllIndices': '复制整表规则的两个序号都必须为 -1',
  'exui.validation.positiveIndices': '序号必须为不小于 1 的整数',
  'exui.validation.chooseOutputFolder': '请选择输出目录',
  'exui.validation.unknownStep': '未知向导步骤：{0}',
  'exui.fallback.analyzeFailed': '分析失败',
  'exui.fallback.configureFailed': '配置失败',
  'exui.fallback.splitFailed': '拆分失败',
  'exui.notify.unableSave': '无法保存向导进度',
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
