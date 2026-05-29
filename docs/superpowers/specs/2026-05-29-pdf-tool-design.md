# PDF Tool Design Spec

## Overview

A single built-in tool (`PdfToolPlugin`) with 3 tabs for PDF split, merge, and convert-to-Word. All three operations are also registered as AI-callable tools for the built-in AI assistant.

## Architecture

```
PdfToolPlugin (createView → TabPane)
├── Tab "拆分"  → PdfSplitPane
├── Tab "合并"  → PdfMergePane
└── Tab "转 Word" → PdfConvertPane

AI tools (via BuiltinAiToolRegistrar):
├── pdf_split   — split PDF by page ranges
├── pdf_merge   — merge multiple PDFs
└── pdf_to_docx — convert PDF to DOCX
```

## Dependencies (add to SwissKit/pom.xml)

| Dependency | Purpose |
|---|---|
| `org.apache.pdfbox:pdfbox` | PDF split/merge operations |
| `com.documents4j:documents4j-local` | Local document conversion bridge |
| `com.documents4j:documents4j-transformer-msoffice-word` | MS Word/LibreOffice transformer |

Note: documents4j uses MS Word COM on Windows and can delegate to LibreOffice on all platforms. WPS support is implemented via a custom `WpsConverter` adapter (no extra dependency). The converter selection is based on `OfficeDetector` results — if WPS is detected, `WpsConverter` is used; otherwise `Documents4jConverter` is used with LibreOffice or MS Word.

## File Structure

```
SwissKit/src/main/java/fan/summer/buildintool/pdftool/
├── PdfToolPlugin.java              — SwissKitJPlugin impl, createView() returns TabPane
├── PdfSplitPane.java               — Split tab content
├── PdfMergePane.java               — Merge tab content
├── PdfConvertPane.java             — Convert-to-Word tab content
├── converter/
│   ├── OfficeDetector.java         — Detect installed Office (WPS/LibreOffice/MS Word)
│   ├── DocumentConverter.java      — Interface: convert(pdfPath, outputPath)
│   ├── Documents4jConverter.java   — documents4j impl (LibreOffice/MS Word)
│   └── WpsConverter.java           — WPS command-line/COM adapter
└── worker/
    ├── PdfSplitWorker.java         — PDFBox split, background Task
    ├── PdfMergeWorker.java         — PDFBox merge, background Task
    └── PdfConvertWorker.java       — Calls DocumentConverter, background Task
```

## Tool Metadata

- **ID**: `builtin.pdf-tool`
- **Category**: `ToolCategory.OTHER`
- **Type**: `ToolType.BUILTIN`
- **Icon**: MDI `file-pdf-box`, `IconStyle.RED`
- **i18n keys**: `builtin.pdf.name`, `builtin.pdf.desc`

## UI Design

### Shared Layout

All three tabs follow the same vertical layout pattern:
1. File selection area (drag-and-drop zone + select button)
2. Configuration area (tab-specific)
3. Output area (directory picker + execute button)
4. Result area (progress bar + result display)

The TabPane uses the glassmorphism theme from `swisskit-common.css` utility classes.

### Split Tab (PdfSplitPane)

- Single PDF file selection (drag-and-drop + button)
- Display file info: name, page count, file size
- Text input for page ranges (e.g. `1-3, 5, 8-10`)
- Output directory picker
- "开始拆分" button
- Output naming: `{originalName}_p{range}.pdf` (e.g. `report_p1-3.pdf`, `report_p5.pdf`)

### Merge Tab (PdfMergePane)

- Multi-file selection (drag-and-drop + add button)
- File list with drag-to-reorder, page count per file, delete button per row
- Summary: total file count + total page count
- Output filename input (default: `merged_output.pdf`)
- Output directory picker
- "开始合并" button

### Convert-to-Word Tab (PdfConvertPane)

- PDF file selection (drag-and-drop + button, supports batch selection)
- Display detected Office backend status:
  - Available: show detected backend name (e.g. "已检测到: WPS Office")
  - Unavailable: show warning + install guidance (download links for WPS/LibreOffice), disable execute button
- Output format: DOCX (fixed)
- "开始转换" button
- Output naming: same filename with `.docx` extension

## Data Flows

### Split

```
Select file → PDFBox reads page count → User enters ranges
→ Validate ranges against page count → PdfSplitWorker (PDFBox Splitter)
→ Output files to directory → Show result list + "open folder" link
```

### Merge

```
Select/drag multiple files → Sortable file list with page counts
→ User sets output name + directory → PdfMergeWorker (PDFBox PDFMergerUtility)
→ Output single merged PDF → Show result
```

### Convert to Word

```
Select file(s) → OfficeDetector checks available backend
→ If none: show warning, disable button
→ PdfConvertWorker calls DocumentConverter implementation
  → Documents4jConverter: delegates to LibreOffice/MS Word via documents4j
  → WpsConverter: macOS command-line / Windows COM call to WPS
→ Output .docx files → Show result list
```

## Office Backend Detection (OfficeDetector)

Detection priority: WPS → LibreOffice → MS Word.

**macOS:**
- WPS: check `/Applications/wpsoffice.app` exists
- LibreOffice: check `/Applications/LibreOffice.app` exists
- MS Word: check `/Applications/Microsoft Word.app` exists

**Windows:**
- WPS: check registry or common install paths
- LibreOffice: check `C:\Program Files\LibreOffice`
- MS Word: check COM registration

**Linux:**
- WPS: check `which wps` or `/opt/kingsoft/wps-office`
- LibreOffice: check `which libreoffice`
- MS Word: N/A on Linux

Result cached after first detection. Converter selected based on detection result.

## WPS Converter (WpsConverter)

WPS Office provides a command-line conversion tool. The exact binary name and path vary by platform and WPS version — `OfficeDetector` resolves the path during detection.

**macOS:** Look for `wps` or `wpp` binary inside the WPS.app bundle. Fallback: try `open -a "wpsoffice" --args --headless` pattern.

**Windows:** WPS exposes COM automation (`Kwps.Application`) similar to MS Word. Use Jacob or direct ProcessBuilder invocation with WPS command-line flags.

**Linux:** WPS installs `wps` binary, typically supports `--headless --convert-to` similar to LibreOffice.

If WPS command-line conversion is unreliable on a given platform, fall back to using documents4j with LibreOffice as the backend. The `OfficeDetector` validates the conversion capability during detection (attempt a small test conversion) and reports which backends are actually functional.

## AI Tool Integration

Three tools registered via `BuiltinAiToolRegistrar`:

### pdf_split
```json
{
  "name": "pdf_split",
  "description": "Split a PDF file into multiple files by page ranges",
  "parameters": {
    "filePath": { "type": "string", "description": "Absolute path to the PDF file" },
    "ranges": { "type": "string", "description": "Page ranges, e.g. '1-3,5,8-10'" },
    "outputDir": { "type": "string", "description": "Output directory for split files" }
  }
}
```

### pdf_merge
```json
{
  "name": "pdf_merge",
  "parameters": {
    "filePaths": { "type": "array", "items": "string", "description": "Ordered list of PDF file paths to merge" },
    "outputPath": { "type": "string", "description": "Output file path for merged PDF" }
  }
}
```

### pdf_to_docx
```json
{
  "name": "pdf_to_docx",
  "parameters": {
    "filePath": { "type": "string", "description": "Absolute path to the PDF file" },
    "outputDir": { "type": "string", "description": "Output directory for the DOCX file" }
  }
}
```

AI tools reuse the same Worker classes. On success, return the output file path(s). On failure, return an error message for the AI to relay to the user.

## Error Handling

**File validation:**
- Reject non-`.pdf` files at selection time
- Split: validate page ranges against actual page count, reject out-of-range
- Merge: require at least 2 files
- Convert: reject encrypted/password-protected PDFs

**Backend unavailable:**
- Convert tab checks `OfficeDetector` on load
- If no backend found: show install guidance, disable execute button
- If backend crashes mid-conversion: catch exception, show retry prompt

**Output conflicts:**
- Auto-append sequence number when output file already exists (e.g. `report_p1-3(1).pdf`)

**AI tool errors:**
- File not found → return error string, AI informs user
- No Office backend → return "未检测到 WPS/LibreOffice/MS Word"

**Large files:**
- All operations run in background `Task<Void>`, UI stays responsive
- Split/merge: progress bar based on page count percentage
- Convert: indeterminate progress animation (external process, no precise progress)

## i18n Keys

Add to `messages.properties` (Chinese) and `messages_en.properties` (English):

```properties
# Chinese
builtin.pdf.name=PDF 工具
builtin.pdf.desc=PDF 拆分、合并与转 Word 工具
builtin.pdf.tab.split=拆分
builtin.pdf.tab.merge=合并
builtin.pdf.tab.convert=转 Word
builtin.pdf.split.hint=请选择 PDF 文件
builtin.pdf.split.ranges=页码范围
builtin.pdf.split.ranges.hint=例如: 1-3, 5, 8-10
builtin.pdf.merge.add=添加文件
builtin.pdf.merge.output=输出文件名
builtin.pdf.convert.no-backend=未检测到 WPS/LibreOffice/MS Word，请安装后重试
builtin.pdf.convert.detecting=正在检测 Office 后端...
builtin.pdf.convert.backend.found=已检测到: {0}
builtin.pdf.execute=开始执行
builtin.pdf.complete=操作完成
```
