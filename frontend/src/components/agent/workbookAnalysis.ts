export interface ExcelWorkbookColumn {
  header?: string
  index?: string
}

export interface ExcelWorkbookSheet {
  name?: string
  columns?: ExcelWorkbookColumn[]
}

export interface ExcelWorkbookAnalysisResponse {
  success?: boolean
  summary?: string
  sheets?: ExcelWorkbookSheet[]
}

/** Convert the Excel UI analyze RPC result into the run form's sheet → column candidates. */
export function workbookOptionsFromAnalysis(
  result: ExcelWorkbookAnalysisResponse | null | undefined,
): Record<string, string[]> {
  if (result?.success === false) {
    throw new Error(result.summary?.trim() || 'Excel workbook analysis failed')
  }

  const options: Record<string, string[]> = {}
  for (const sheet of result?.sheets ?? []) {
    if (!sheet || typeof sheet.name !== 'string' || !sheet.name.trim()) continue
    const headers = (sheet.columns ?? [])
      .map((column) => column?.header)
      .filter((header): header is string => typeof header === 'string' && !!header.trim())
    options[sheet.name] = [...new Set(headers)]
  }
  return options
}
