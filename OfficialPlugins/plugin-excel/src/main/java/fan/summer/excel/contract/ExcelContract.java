package fan.summer.excel.contract;

import fan.summer.fengyu.sdk.RpcContext;
import fan.summer.fengyu.sdk.contract.FengYuAiTool;
import fan.summer.fengyu.sdk.contract.FengYuContract;
import fan.summer.fengyu.sdk.contract.FengYuField;
import fan.summer.fengyu.sdk.contract.FengYuRpc;
import java.util.List;

/** RPC contract for fan.summer.excel — migrated from the manifest-first manifest.json. */
public interface ExcelContract {
    @FengYuContract
    interface UiWorkflowRpc {
    @FengYuRpc(name = "analyze", description = "Analyze a workbook on the session-keyed UI workflow; returns sheet names + header columns.")
    AnalyzeOutput analyze(AnalyzeInput input, RpcContext context);

    @FengYuRpc(name = "configure", description = "Apply the split configuration onto the session (mode + mode-specific fields).")
    ConfigureOutput configure(ConfigureInput input, RpcContext context);

    @FengYuRpc(name = "estimate", description = "Estimate the output-file count for the session's current configuration without writing anything.")
    EstimateOutput estimate(EstimateInput input, RpcContext context);

    @FengYuRpc(name = "split", description = "Execute a configured split synchronously and write output files. Carries the full config so a worker restarted between configure and split can re-apply it.")
    SplitOutput split(SplitInput input, RpcContext context);

    @FengYuRpc(name = "split_cancel", description = "Cancel a running UI split job by id (domain cancellation of a background job — distinct from transport $/cancelRequest of the current RPC).")
    SplitCancelOutput split_cancel(SplitCancelInput input, RpcContext context);

    @FengYuRpc(name = "split_start", description = "Launch the configured split as a background job and return its job id immediately (large workbooks). Poll with split_status, cancel with split_cancel.")
    SplitStartOutput split_start(SplitStartInput input, RpcContext context);

    @FengYuRpc(name = "split_status", description = "Poll a UI split job: streamed logs (from cursor) and final result.")
    SplitStatusOutput split_status(SplitStatusInput input, RpcContext context);
    }

    @FengYuContract
    interface AiWorkflowRpc {
    @FengYuRpc(name = "excel_analyze", description = "AI: analyze the granted workbook into the shared AI session; returns sheet names.")
    @FengYuAiTool(description = "Analyze the granted Excel workbook before configuring or executing any split; returns sheet names.", effect = FengYuAiTool.ToolEffect.READ, timeoutSeconds = 30)
    ExcelAnalyzeOutput excel_analyze(ExcelAnalyzeInput input, RpcContext context);

    @FengYuRpc(name = "excel_cancel", description = "AI: clear the active Excel split session.")
    @FengYuAiTool(description = "Clear the active Excel split session.", effect = FengYuAiTool.ToolEffect.READ, timeoutSeconds = 30)
    ExcelCancelOutput excel_cancel(ExcelCancelInput input, RpcContext context);

    @FengYuRpc(name = "excel_complex_config", description = "AI: add, list, or clear complex split rules on the shared AI session.")
    @FengYuAiTool(description = "Add (one or many), list, or clear complex split rules; a filePath input analyzes the workbook in the same call.", effect = FengYuAiTool.ToolEffect.READ, timeoutSeconds = 30)
    ExcelComplexConfigOutput excel_complex_config(ExcelComplexConfigInput input, RpcContext context);

    @FengYuRpc(name = "excel_configure", description = "AI: configure the split mode (and BY_SHEET/BY_COLUMN specifics) on the shared AI session.")
    @FengYuAiTool(description = "Configure sheet, column, or complex splitting.", effect = FengYuAiTool.ToolEffect.READ, timeoutSeconds = 30)
    ExcelConfigureOutput excel_configure(ExcelConfigureInput input, RpcContext context);

    @FengYuRpc(name = "excel_execute", description = "AI: execute the configured split synchronously into outputDir (small workbooks).")
    @FengYuAiTool(description = "Execute a configured split synchronously for a small workbook.", effect = FengYuAiTool.ToolEffect.WRITE, timeoutSeconds = 60)
    ExcelExecuteOutput excel_execute(ExcelExecuteInput input, RpcContext context);

    @FengYuRpc(name = "excel_execute_start", description = "AI: launch the configured split as a background job and return its job id (large workbooks). Poll with excel_execute_status.")
    @FengYuAiTool(description = "Start a configured split job and return its job ID.", effect = FengYuAiTool.ToolEffect.WRITE, timeoutSeconds = 30)
    ExcelExecuteStartOutput excel_execute_start(ExcelExecuteStartInput input, RpcContext context);

    @FengYuRpc(name = "excel_execute_status", description = "AI: poll an Excel split job and return streamed logs and its final result.")
    @FengYuAiTool(description = "Poll an Excel split job and return logs and its final result.", effect = FengYuAiTool.ToolEffect.READ, timeoutSeconds = 30)
    ExcelExecuteStatusOutput excel_execute_status(ExcelExecuteStatusInput input, RpcContext context);

    @FengYuRpc(name = "excel_query", description = "AI: return the current Excel split session state.")
    @FengYuAiTool(description = "Return the current Excel split session state.", effect = FengYuAiTool.ToolEffect.READ, timeoutSeconds = 30)
    ExcelQueryOutput excel_query(ExcelQueryInput input, RpcContext context);

    }

    public record AnalyzeInput(
        @FengYuField(description = "UI workflow session id.", required = true)
        String session,
        @FengYuField(description = "Resolved absolute path of a readable FengYu FileRef (the host resolves the ref before the worker receives it).", required = true)
        String sourceFile
    ) {}

    public record AnalyzeOutput(
        @FengYuField(description = "One entry per analyzed sheet.")
        List<AnalyzeOutputSheets> sheets,
        @FengYuField(description = "true when analysis completed.")
        boolean success,
        @FengYuField(description = "Short localized result summary.", required = true)
        String summary
    ) {
      public record AnalyzeOutputSheets(
          @FengYuField(description = "Header columns of the sheet.")
          List<AnalyzeOutputSheetsColumns> columns,
          @FengYuField(description = "Sheet name.")
          String name
      ) {
        public record AnalyzeOutputSheetsColumns(
            @FengYuField(description = "Header cell text (empty when blank).")
            String header,
            @FengYuField(description = "0-based column index (stringified).")
            String index
        ) {}
      }
    }

    public record ConfigureInput(
        @FengYuField(description = "COMPLEX split rules.")
        List<ConfigureInputComplexEntries> complexEntries,
        @FengYuField(description = "Prefix prepended to every output file name.")
        String filePrefix,
        @FengYuField(description = "Split mode.", required = true)
        ConfigureInputMode mode,
        @FengYuField(description = "BY_SHEET selection; omit to use all analyzed sheets.")
        List<String> selectedSheets,
        @FengYuField(description = "UI workflow session id.", required = true)
        String session,
        @FengYuField(description = "BY_COLUMN header text to split on.")
        String splitColumn,
        @FengYuField(description = "BY_COLUMN resolved column index (optional; resolved from header text when absent).")
        Integer splitColumnIndex,
        @FengYuField(description = "BY_COLUMN source sheet.")
        String splitSheet
    ) {
      public record ConfigureInputComplexEntries(
          @FengYuField(description = "1-based split column; -1 with headerIndex -1 = copy entire sheet.")
          Integer columnIndex,
          @FengYuField(description = "Original source file name (informational).")
          String fieldName,
          @FengYuField(description = "1-based header row; -1 with columnIndex -1 = copy entire sheet.")
          Integer headerIndex,
          @FengYuField(description = "Sheet this rule applies to.")
          String sheetName
      ) {}

      public enum ConfigureInputMode {
        BY_SHEET,
        BY_COLUMN,
        COMPLEX
      }
    }

    public record ConfigureOutput(
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record EstimateInput(
        @FengYuField(description = "UI workflow session id.", required = true)
        String session
    ) {}

    public record EstimateOutput(
        @FengYuField(description = "true — the estimate is exact for the current engine.")
        Boolean exact,
        @FengYuField(description = "Estimated number of output files.")
        Integer fileCount,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record ExcelAnalyzeInput(
        @FengYuField(description = "Resolved absolute path of a readable FengYu FileRef for the workbook.", required = true)
        String filePath,
        @FengYuField(description = "Host-injected run scoping; omit to share the single chat session.", nullable = true)
        String sessionId
    ) {}

    public record ExcelAnalyzeOutput(
        @FengYuField(description = "Workbook sheet names.")
        List<String> sheets,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record ExcelCancelInput(
        @FengYuField(description = "Host-injected run scoping; omit to share the single chat session.", nullable = true)
        String sessionId
    ) {}

    public record ExcelCancelOutput(
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record ExcelComplexConfigInput(
        @FengYuField(required = true, defaultValue = "add")
        ExcelComplexConfigInputAction action,
        @FengYuField(description = "Single-rule shorthand for action=add; prefer the entries array for complex splits.", advanced = true)
        Integer columnIndex,
        @FengYuField(description = "Multiple complex rules in one call (action=add); replaces the single sheetName/headerIndex/columnIndex fields.")
        List<ExcelComplexConfigInputEntries> entries,
        @FengYuField(description = "Resolved absolute path of a readable FengYu FileRef for the workbook. With action=add the workbook is analyzed first, so one call can configure a complete complex split.", analyze = "excel")
        String filePath,
        @FengYuField(description = "Single-rule shorthand for action=add; prefer the entries array for complex splits.", advanced = true)
        Integer headerIndex,
        @FengYuField(description = "Host-injected run scoping; omit to share the single chat session.", nullable = true)
        String sessionId,
        @FengYuField(description = "Single-rule shorthand for action=add; prefer the entries array for complex splits.", advanced = true)
        String sheetName
    ) {
      public enum ExcelComplexConfigInputAction {
        add,
        list,
        clear
      }

      public record ExcelComplexConfigInputEntries(
          @FengYuField(description = "1 基拆分列号（按该列取值分组拆分）；整表拷贝时忽略。", title = "拆分列号")
          Integer columnIndex,
          @FengYuField(description = "Header text to split on; resolved to columnIndex against the analysis when columnIndex is absent.", title = "拆分列", advanced = true, optionsFrom = "workbook-columns")
          String columnName,
          @FengYuField(description = "勾选后整份复制该工作表，忽略拆分列。", title = "整表拷贝")
          Boolean copyEntireSheet,
          @FengYuField(description = "1 基表头行号；默认 1（第一行），整表拷贝时忽略。", title = "表头行号")
          Integer headerIndex,
          @FengYuField(description = "Sheet this rule applies to.", title = "Sheet 名称", required = true, optionsFrom = "workbook-sheets")
          String sheetName
      ) {}
    }

    public record ExcelComplexConfigOutput(
        @FengYuField(description = "Configured complex split rules (action=list).")
        List<ExcelComplexConfigOutputEntries> entries,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record ExcelComplexConfigOutputEntries(
          Integer columnIndex,
          Integer headerIndex,
          String sheetName
      ) {}
    }

    public record ExcelConfigureInput(
        @FengYuField(required = true)
        ExcelConfigureInputMode mode,
        @FengYuField(description = "Host-injected run scoping; omit to share the single chat session.", nullable = true)
        String sessionId,
        @FengYuField(description = "BY_SHEET selection; omit to use all sheets.")
        List<String> sheets,
        String splitColumn,
        String splitSheet
    ) {
      public enum ExcelConfigureInputMode {
        BY_SHEET,
        BY_COLUMN,
        COMPLEX
      }
    }

    public record ExcelConfigureOutput(
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record ExcelExecuteInput(
        String filePrefix,
        @FengYuField(description = "Resolved absolute path of a writable FengYu DirectoryRef; leave empty to write into the plugin default output folder.", required = true)
        String outputDir,
        @FengYuField(description = "Host-injected run scoping; omit to share the single chat session.", nullable = true)
        String sessionId
    ) {}

    public record ExcelExecuteOutput(
        @FengYuField(description = "Generated file count and names.")
        ExcelExecuteOutputFiles files,
        @FengYuField(description = "Absolute path of the directory the split files were actually written to — bind it directly as a downstream attachment directory (e.g. email_send_batch.inputDirectory).")
        String outputDir,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record ExcelExecuteOutputFiles(
          Integer fileCount,
          List<String> files
      ) {}
    }

    public record ExcelExecuteStartInput(
        String filePrefix,
        @FengYuField(description = "Resolved absolute path of a writable FengYu DirectoryRef; leave empty to write into the plugin default output folder.", required = true)
        String outputDir,
        @FengYuField(description = "Host-injected run scoping; omit to share the single chat session.", nullable = true)
        String sessionId
    ) {}

    public record ExcelExecuteStartOutput(
        @FengYuField(description = "Job identifier for status polling.")
        String jobId,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record ExcelExecuteStatusInput(
        @FengYuField(minimum = 0)
        Integer cursor,
        @FengYuField(required = true)
        String jobId,
        @FengYuField(description = "Host-injected run scoping; omit to share the single chat session.", nullable = true)
        String sessionId
    ) {}

    public record ExcelExecuteStatusOutput(
        Integer cursor,
        Boolean done,
        Integer droppedLogs,
        Integer elapsedMs,
        @FengYuField(nullable = true)
        String error,
        String jobId,
        List<String> logs,
        @FengYuField(nullable = true)
        ExcelExecuteStatusOutputResult result,
        String status,
        boolean success,
        @FengYuField(required = true)
        String summary,
        String type
    ) {
      public record ExcelExecuteStatusOutputResult(
          Integer fileCount,
          List<String> files
      ) {}
    }

    public record ExcelQueryInput(
        @FengYuField(description = "Host-injected run scoping; omit to share the single chat session.", nullable = true)
        String sessionId
    ) {}

    public record ExcelQueryOutput(
        @FengYuField(description = "Current Excel split session state.")
        ExcelQueryOutputState state,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {
      public record ExcelQueryOutputState(
          @FengYuField(description = "Number of configured complex rules.")
          Integer complexEntries,
          @FengYuField(nullable = true)
          String mode,
          @FengYuField(nullable = true)
          String outputDir,
          List<String> selectedSheets,
          @FengYuField(nullable = true)
          String sourceFile,
          Integer splitColumnIndex,
          @FengYuField(nullable = true)
          String splitSheet
      ) {}
    }

    public record SplitInput(
        List<SplitInputComplexEntries> complexEntries,
        String filePrefix,
        SplitInputMode mode,
        @FengYuField(description = "Resolved absolute path of a writable FengYu DirectoryRef; leave empty to write into the plugin default output folder.", required = true)
        String outputDir,
        List<String> selectedSheets,
        @FengYuField(description = "UI workflow session id.", required = true)
        String session,
        @FengYuField(description = "Resolved absolute path of a readable FengYu FileRef.", required = true)
        String sourceFile,
        String splitColumn,
        Integer splitColumnIndex,
        String splitSheet
    ) {
      public record SplitInputComplexEntries(
          Integer columnIndex,
          String fieldName,
          Integer headerIndex,
          String sheetName
      ) {}

      public enum SplitInputMode {
        BY_SHEET,
        BY_COLUMN,
        COMPLEX
      }
    }

    public record SplitOutput(
        @FengYuField(description = "Number of files written.")
        Integer fileCount,
        @FengYuField(description = "Names of the written files.")
        List<String> files,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record SplitCancelInput(
        @FengYuField(required = true)
        String jobId
    ) {}

    public record SplitCancelOutput(
        String jobId,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record SplitStartInput(
        String filePrefix,
        @FengYuField(description = "Resolved absolute path of a writable FengYu DirectoryRef; leave empty to write into the plugin default output folder.", required = true)
        String outputDir,
        @FengYuField(description = "UI workflow session id (must already be analyzed + configured).", required = true)
        String session
    ) {}

    public record SplitStartOutput(
        @FengYuField(description = "Job identifier for status polling / cancellation.")
        String jobId,
        boolean success,
        @FengYuField(required = true)
        String summary
    ) {}

    public record SplitStatusInput(
        @FengYuField(description = "Absolute log cursor returned by a previous poll; 0 for the tail from the start.", minimum = 0)
        Integer cursor,
        @FengYuField(required = true)
        String jobId
    ) {}

    public record SplitStatusOutput(
        @FengYuField(description = "Absolute cursor after this read.")
        Integer cursor,
        @FengYuField(description = "true once the job has reached a terminal state.")
        Boolean done,
        @FengYuField(description = "Number of oldest log lines evicted by the retention cap.")
        Integer droppedLogs,
        @FengYuField(description = "Wall-clock time since the job started.")
        Integer elapsedMs,
        @FengYuField(description = "Failure detail (status=FAILED only).", nullable = true)
        String error,
        String jobId,
        @FengYuField(description = "Log lines from the cursor onward.")
        List<String> logs,
        @FengYuField(description = "Split summary (present on success).", nullable = true)
        SplitStatusOutputResult result,
        @FengYuField(description = "RUNNING / DONE / FAILED / CANCELLED.")
        String status,
        boolean success,
        @FengYuField(required = true)
        String summary,
        @FengYuField(description = "Job type label (SPLIT).")
        String type
    ) {
      public record SplitStatusOutputResult(
          Integer fileCount,
          List<String> files
      ) {}
    }

}
