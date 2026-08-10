/**
 * Client-side i18n for the Excel Splitter UI. The host pushes the active locale through
 * `environment` events; this module ships the `exui.*` message map for en/zh and picks the matching
 * table. (Frontend keys use the `exui.` prefix to stay distinct from the backend worker's `ex.*`
 * keys.) Mirrors the offlinepython/markdown frontend i18n shape (flat keys + messagesFor/format).
 *
 * Both tables MUST keep identical key sets so neither locale ever renders a raw key.
 */
export type Messages = Record<string, string>

const en: Messages = {
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
  'exui.source.chooseFile': 'Choose Excel file',
  'exui.source.selected': 'Selected: {0}',
  'exui.source.analyzing': 'Analyzing…',
  // Mode step — cards
  'exui.mode.bySheet.label': 'By sheet',
  'exui.mode.bySheet.hint': 'One file per sheet',
  'exui.mode.byColumn.label': 'By column',
  'exui.mode.byColumn.hint': 'One file per value in a column',
  'exui.mode.complex.label': 'Complex',
  'exui.mode.complex.hint': 'Multiple rules combined',
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
  // Output step — config summary
  'exui.output.configTitle': 'Split configuration',
  'exui.output.mode': 'Mode',
  'exui.output.rules': 'Rules',
  'exui.output.expectedFiles': 'Expected files',
  'exui.output.estimating': 'estimating…',
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
  // Output step — pickers + alerts
  'exui.output.chooseFolder': 'Choose output folder',
  'exui.output.outputName': 'Output: {0}',
  'exui.output.desktopHint': 'Files are written directly into this folder — no download step needed.',
  'exui.output.webHint': 'Results are staged in a temporary folder; after the split you can download them as a zip.',
  // Run step
  'exui.run.splitting': 'Splitting…',
  // Complete step
  'exui.complete.written': '{0} file(s) written to {1}',
  'exui.complete.outputFolderFallback': 'the output folder',
  'exui.complete.outputFolder': 'Output folder: {0}',
  'exui.complete.download': 'Download results',
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
  'exui.source.chooseFile': '选择 Excel 文件',
  'exui.source.selected': '已选择：{0}',
  'exui.source.analyzing': '正在分析…',
  'exui.mode.bySheet.label': '按工作表',
  'exui.mode.bySheet.hint': '每个工作表一个文件',
  'exui.mode.byColumn.label': '按列',
  'exui.mode.byColumn.hint': '按某列的每个值拆分',
  'exui.mode.complex.label': '复杂',
  'exui.mode.complex.hint': '组合多条规则',
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
  'exui.output.configTitle': '拆分配置',
  'exui.output.mode': '方式',
  'exui.output.rules': '规则',
  'exui.output.expectedFiles': '预期文件数',
  'exui.output.estimating': '估算中…',
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
  'exui.output.outputName': '输出：{0}',
  'exui.output.desktopHint': '文件将直接写入此目录，无需下载。',
  'exui.output.webHint': '结果先暂存在临时目录；拆分完成后可作为 zip 下载。',
  'exui.run.splitting': '正在拆分…',
  'exui.complete.written': '已写入 {0} 个文件到 {1}',
  'exui.complete.outputFolderFallback': '输出目录',
  'exui.complete.outputFolder': '输出目录：{0}',
  'exui.complete.download': '下载结果',
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
