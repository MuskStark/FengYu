package fan.summer.buildintool.ai;

import fan.summer.api.ai.*;
import fan.summer.ai.util.JsonHelper;
import fan.summer.buildintool.excelsplitter.*;
import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.excel.ComplexSplitConfigEntity;
import fan.summer.database.mapper.excel.ComplexSplitConfigMapper;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;
import org.apache.ibatis.session.SqlSession;

import java.util.*;

/**
 * AI tool that manages complex split configurations stored in the database.
 *
 * <p>Supports three actions:</p>
 * <ul>
 *   <li>{@code add} — inserts one config row (sheet, header row, split column).
 *       Auto-generates a {@code taskId} if not provided. Returns the {@code taskId}
 *       so it can be passed to {@link ExcelConfigureTool} with mode=COMPLEX.</li>
 *   <li>{@code list} — returns all config rows for a given {@code taskId}.</li>
 *   <li>{@code clear} — deletes all config rows for a given {@code taskId}.</li>
 * </ul>
 *
 * @see ExcelConfigureTool
 * @see ComplexSplitConfigEntity
 */
public class ExcelComplexConfigTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(ExcelComplexConfigTool.class);
    private final ExcelSplitterPlugin plugin;

    public ExcelComplexConfigTool(ExcelSplitterPlugin plugin) { this.plugin = plugin; }

    @Override public String getName() { return "excel_complex_config"; }

    @Override public String getDescription() {
        return "Manage database-backed complex split configs used by COMPLEX mode.\n"
             + "Args: action (string, required, enum: add|list|clear) — operation;\n"
             + "      taskId (string, optional) — task ID (auto-generated on 'add' if omitted);\n"
             + "      sheetName (string, add) — sheet name;\n"
             + "      headerIndex (integer, add) — 1-based header row; -1 = copy all;\n"
             + "      columnIndex (integer, add) — 1-based column to split by; -1 = copy to all.\n"
             + "Example: excel_complex_config{\"action\":\"add\",\"sheetName\":\"Sheet1\",\"headerIndex\":1,\"columnIndex\":2}.";
    }

    @Override public String getLocalDescription() {
        return "Manage complex split configs. Args: action (add|list|clear), plus action-specific.\n"
             + "Example: excel_complex_config{\"action\":\"list\",\"taskId\":\"t1\"}.";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("action", "string", "Action: add, list, or clear", true,
                List.of("add", "list", "clear")),
            AiToolParam.of("taskId", "string", "Task ID (auto-generated on 'add' if omitted)", false),
            AiToolParam.of("sheetName", "string", "Sheet name (required for 'add')", false),
            AiToolParam.of("headerIndex", "integer", "1-based header row; -1 = no header / copy all (required for 'add')", false),
            AiToolParam.of("columnIndex", "integer", "1-based column to split by; -1 = copy to all outputs (required for 'add')", false)
        );
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        String action = (String) args.get("action");
        if (action == null || action.isBlank()) {
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "action is required (add, list, or clear)")));
        }

        try {
            return switch (action.toLowerCase()) {
                case "add"  -> doAdd(args);
                case "list" -> doList(args);
                case "clear"-> doClear(args);
                default -> AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "Unknown action: " + action + ". Use add, list, or clear.")));
            };
        } catch (Exception e) {
            log.error("excel_complex_config {} failed: {}", action, e.getMessage());
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", action + " failed: " + e.getMessage())));
        }
    }

    private AiToolResult doAdd(Map<String, Object> args) {
        String sheetName = (String) args.get("sheetName");
        if (sheetName == null || sheetName.isBlank()) {
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "sheetName is required for add action")));
        }

        Object headerObj = args.get("headerIndex");
        Object colObj    = args.get("columnIndex");
        if (headerObj == null || colObj == null) {
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "headerIndex and columnIndex are required for add action")));
        }
        int headerIndex = ((Number) headerObj).intValue();
        int columnIndex = ((Number) colObj).intValue();

        SplitConfig config = plugin.getSharedSplitConfig();
        String taskId = (String) args.get("taskId");
        if (taskId == null || taskId.isBlank()) {
            taskId = UUID.randomUUID().toString();
        }
        config.complexTaskId = taskId;

        String fieldName = config.sourceFile != null ? config.sourceFile.getFileName().toString() : "";

        ComplexSplitConfigEntity entity = new ComplexSplitConfigEntity();
        entity.setTaskId(taskId);
        entity.setFieldName(fieldName);
        entity.setSheetName(sheetName);
        entity.setHeaderIndex(headerIndex);
        entity.setColumnIndex(columnIndex);

        try (SqlSession session = DatabaseInit.getSqlSession()) {
            ComplexSplitConfigMapper mapper = session.getMapper(ComplexSplitConfigMapper.class);
            mapper.insert(entity);
            session.commit();
        }

        int total = countConfigs(taskId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("taskId", taskId);
        result.put("addedSheet", sheetName);
        result.put("addedHeaderIndex", headerIndex);
        result.put("addedColumnIndex", columnIndex);
        result.put("totalConfigs", total);
        result.put("summary", "Added config for sheet '" + sheetName + "' (taskId: " + taskId + ", total configs: " + total + ")");

        log.info("Complex config added: sheet={}, header={}, col={}, taskId={}", sheetName, headerIndex, columnIndex, taskId);
        return AiToolResult.success(JsonHelper.toJson(result));
    }

    private AiToolResult doList(Map<String, Object> args) {
        String taskId = (String) args.get("taskId");
        if (taskId == null || taskId.isBlank()) {
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "taskId is required for list action")));
        }

        List<ComplexSplitConfigEntity> rows;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            ComplexSplitConfigMapper mapper = session.getMapper(ComplexSplitConfigMapper.class);
            rows = mapper.selectAllByTaskId(taskId);
        }

        List<Map<String, Object>> configs = new ArrayList<>();
        for (ComplexSplitConfigEntity r : rows) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", r.getId());
            c.put("sheetName", r.getSheetName());
            c.put("headerIndex", r.getHeaderIndex());
            c.put("columnIndex", r.getColumnIndex());
            boolean isCopyAll = Integer.valueOf(-1).equals(r.getHeaderIndex())
                             && Integer.valueOf(-1).equals(r.getColumnIndex());
            c.put("type", isCopyAll ? "copyAll" : "normalSplit");
            configs.add(c);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("summary", "Found " + configs.size() + " config(s) for taskId " + taskId);
        result.put("taskId", taskId);
        result.put("configs", configs);
        result.put("total", configs.size());

        return AiToolResult.success(JsonHelper.toJson(result));
    }

    private AiToolResult doClear(Map<String, Object> args) {
        String taskId = (String) args.get("taskId");
        if (taskId == null || taskId.isBlank()) {
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "taskId is required for clear action")));
        }

        try (SqlSession session = DatabaseInit.getSqlSession()) {
            ComplexSplitConfigMapper mapper = session.getMapper(ComplexSplitConfigMapper.class);
            mapper.deleteAllByTaskId(taskId);
            session.commit();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("taskId", taskId);
        result.put("summary", "All configs cleared for taskId: " + taskId);

        log.info("Complex configs cleared for taskId: {}", taskId);
        return AiToolResult.success(JsonHelper.toJson(result));
    }

    private int countConfigs(String taskId) {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            ComplexSplitConfigMapper mapper = session.getMapper(ComplexSplitConfigMapper.class);
            return mapper.selectAllByTaskId(taskId).size();
        }
    }
}
