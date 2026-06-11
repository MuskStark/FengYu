package fan.summer.buildintool.ai;

import fan.summer.api.ai.*;
import fan.summer.ai.util.JsonHelper;
import fan.summer.buildintool.excelsplitter.*;
import java.util.*;

/**
 * AI tool that configures the Excel split mode and its parameters.
 *
 * <p>Must be called after {@link ExcelAnalyzeTool} has populated the shared
 * {@link SplitConfig} with an analysis result. This tool sets the split mode
 * and any associated parameters, but does not execute the split itself.</p>
 *
 * <p>Supported modes:</p>
 * <ul>
 *   <li>{@code BY_SHEET} — one output file per sheet. Optional {@code sheets} arg
 *       selects a subset; otherwise all sheets are exported.</li>
 *   <li>{@code BY_COLUMN} — groups rows by unique values in a column and produces
 *       one output file per distinct value. Requires {@code splitSheet} and
 *       {@code splitColumn} arguments.</li>
 *   <li>{@code COMPLEX} — multi-configuration split backed by the database.
 *       Requires a {@code taskId} (UUID string) that identifies the configuration
 *       stored in the database.</li>
 * </ul>
 *
 * <p>Required arguments:</p>
 * <ul>
 *   <li>{@code mode} (string) — one of {@code BY_SHEET}, {@code BY_COLUMN}, or {@code COMPLEX}</li>
 * </ul>
 *
 * <p>Conditional arguments:</p>
 * <ul>
 *   <li>{@code sheets} (string[]) — sheet names to export (BY_SHEET mode, optional)</li>
 *   <li>{@code splitSheet} (string) — sheet name to split on (BY_COLUMN mode)</li>
 *   <li>{@code splitColumn} (string) — column header name to split by (BY_COLUMN mode)</li>
 *   <li>{@code taskId} (string) — complex split task ID from the database (COMPLEX mode)</li>
 * </ul>
 *
 * @see ExcelAnalyzeTool
 * @see ExcelExecuteTool
 * @see ExcelQueryTool
 */

@SuppressWarnings("unchecked")
public class ExcelConfigureTool implements AiTool {
    private final ExcelSplitterPlugin plugin;

    public ExcelConfigureTool(ExcelSplitterPlugin plugin) { this.plugin = plugin; }

    @Override public String getName() { return "excel_configure"; }

    @Override public String getDescription() {
        return "Configure the Excel split mode and parameters. " +
               "Must be called after excel_analyze. " +
               "Modes: BY_SHEET (one file per sheet), BY_COLUMN (group by column value), " +
               "COMPLEX (DB-backed multi-config). " +
               "Required args: mode (string). " +
               "BY_SHEET optional: sheets (string[]). " +
               "BY_COLUMN required: splitSheet (string), splitColumn (string). " +
               "COMPLEX required: taskId (string, UUID).";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("mode", "string", "Split mode: BY_SHEET, BY_COLUMN, or COMPLEX", true),
            AiToolParam.of("sheets", "string[]", "Sheet names to export (BY_SHEET mode)", false),
            AiToolParam.of("splitSheet", "string", "Sheet name to split on (BY_COLUMN mode)", false),
            AiToolParam.of("splitColumn", "string", "Column header name to split by (BY_COLUMN mode)", false),
            AiToolParam.of("taskId", "string", "Complex split task ID from DB (COMPLEX mode)", false)
        );
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        SplitConfig config = plugin.getSharedSplitConfig();

        if (config.analysisResult == null) {
            return AiToolResult.error("No analysis result. Call excel_analyze first.");
        }

        String modeStr = (String) args.get("mode");
        if (modeStr == null || modeStr.isBlank()) {
            return AiToolResult.error("mode is required (BY_SHEET, BY_COLUMN, or COMPLEX)");
        }

        try {
            SplitConfig.SplitMode mode = SplitConfig.SplitMode.valueOf(modeStr.toUpperCase());
            config.mode = mode;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("configured", true);
            result.put("mode", mode.name());

            switch (mode) {
                case BY_SHEET -> {
                    List<String> sheets = (List<String>) args.get("sheets");
                    if (sheets != null && !sheets.isEmpty()) {
                        config.selectedSheets = new ArrayList<>(sheets);
                    } else {
                        config.selectedSheets = new ArrayList<>(config.analysisResult.keySet());
                    }
                    result.put("selectedSheets", config.selectedSheets);
                    result.put("summary", "Will export " + config.selectedSheets.size() + " sheet(s) as separate files");
                }
                case BY_COLUMN -> {
                    String splitSheet = (String) args.get("splitSheet");
                    String splitColumn = (String) args.get("splitColumn");
                    if (splitSheet == null || splitColumn == null) {
                        return AiToolResult.error("BY_COLUMN mode requires splitSheet and splitColumn");
                    }
                    Map<Integer, String> headers = config.analysisResult.get(splitSheet);
                    if (headers == null) {
                        return AiToolResult.error("Sheet not found: " + splitSheet);
                    }
                    Integer colIdx = null;
                    String foundCol = null;
                    for (Map.Entry<Integer, String> e : headers.entrySet()) {
                        String headerName = e.getValue();
                        if (headerName != null && headerName.equalsIgnoreCase(splitColumn.trim())) {
                            colIdx = e.getKey();
                            foundCol = headerName;
                            break;
                        }
                    }
                    if (colIdx == null) {
                        return AiToolResult.error("Column not found: " + splitColumn + ". Available columns: " + headers.values());
                    }
                    config.splitSheet = splitSheet;
                    config.splitColumn = foundCol;
                    config.splitColumnIndex = colIdx;
                    result.put("splitSheet", splitSheet);
                    result.put("splitColumn", foundCol);
                    result.put("splitColumnIndex", colIdx);
                    result.put("summary", "Will split sheet '" + splitSheet + "' by column '" + foundCol + "' (index " + colIdx + ")");
                }
                case COMPLEX -> {
                    String taskId = (String) args.get("taskId");
                    if (taskId == null || taskId.isBlank()) {
                        return AiToolResult.error("COMPLEX mode requires taskId (UUID string)");
                    }
                    config.complexTaskId = taskId;
                    result.put("taskId", taskId);
                    result.put("summary", "Complex split configured with taskId: " + taskId);
                }
            }

            return AiToolResult.success(JsonHelper.toJson(result));
        } catch (IllegalArgumentException e) {
            return AiToolResult.error("Invalid mode: " + modeStr + ". Use BY_SHEET, BY_COLUMN, or COMPLEX.");
        }
    }
}