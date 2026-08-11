package fan.summer.fengyu.plugin.excel;

import fan.summer.fengyu.sdk.JsonRpcWorker;
import fan.summer.fengyu.sdk.RpcContext;
import fan.summer.excel.generated.AnalyzeInput;
import fan.summer.excel.generated.AnalyzeOutput;
import fan.summer.excel.generated.ConfigureInput;
import fan.summer.excel.generated.ConfigureOutput;
import fan.summer.excel.generated.EstimateInput;
import fan.summer.excel.generated.EstimateOutput;
import fan.summer.excel.generated.ExcelAnalyzeInput;
import fan.summer.excel.generated.ExcelAnalyzeOutput;
import fan.summer.excel.generated.ExcelCancelInput;
import fan.summer.excel.generated.ExcelCancelOutput;
import fan.summer.excel.generated.ExcelComplexConfigInput;
import fan.summer.excel.generated.ExcelComplexConfigOutput;
import fan.summer.excel.generated.ExcelConfigureInput;
import fan.summer.excel.generated.ExcelConfigureOutput;
import fan.summer.excel.generated.ExcelExecuteInput;
import fan.summer.excel.generated.ExcelExecuteOutput;
import fan.summer.excel.generated.ExcelExecuteStartInput;
import fan.summer.excel.generated.ExcelExecuteStartOutput;
import fan.summer.excel.generated.ExcelExecuteStatusInput;
import fan.summer.excel.generated.ExcelExecuteStatusOutput;
import fan.summer.excel.generated.ExcelQueryInput;
import fan.summer.excel.generated.ExcelQueryOutput;
import fan.summer.excel.generated.PluginMethods;
import fan.summer.excel.generated.SplitCancelInput;
import fan.summer.excel.generated.SplitCancelOutput;
import fan.summer.excel.generated.SplitInput;
import fan.summer.excel.generated.SplitOutput;
import fan.summer.excel.generated.SplitStartInput;
import fan.summer.excel.generated.SplitStartOutput;
import fan.summer.excel.generated.SplitStatusInput;
import fan.summer.excel.generated.SplitStatusOutput;

/**
 * Excel Splitter worker. Speaks newline-delimited JSON-RPC 2.0 on stdio. Every method is registered
 * through the typed {@link JsonRpcWorker#method} API: the SDK deserializes the incoming params into
 * the generated {@code *Input} record, binds an {@link RpcContext} to the handler thread, invokes the
 * matching {@link ExcelRpcHandlers} method, and serializes the returned {@code *Output} record back
 * into the response. Methods are split into the session-keyed UI workflow
 * ({@code analyze}/{@code configure}/{@code estimate}/{@code split}) and the stateless AI tools
 * ({@code excel_*}) declared in {@code manifest.json}; both share one {@link ExcelRpcHandlers}.
 * {@link JsonRpcWorker#run()} redirects stdout to stderr so the protocol stream on stdout stays clean.
 */
public final class ExcelWorkerMain {
    private ExcelWorkerMain() {}

    public static void main(String[] args) throws Exception {
        ExcelSessionStore sessions = new ExcelSessionStore();
        ExcelRpcHandlers handlers = new ExcelRpcHandlers(sessions);
        worker(handlers).run();
    }

    static JsonRpcWorker worker(ExcelRpcHandlers handlers) {
        return new JsonRpcWorker()
            .onClose(handlers)
            // UI-facing, session-keyed workflow.
            .method(PluginMethods.ANALYZE, AnalyzeInput.class, AnalyzeOutput.class,
                (AnalyzeInput in, RpcContext ctx) -> handlers.analyze(in, ctx))
            .method(PluginMethods.CONFIGURE, ConfigureInput.class, ConfigureOutput.class,
                (ConfigureInput in, RpcContext ctx) -> handlers.configure(in, ctx))
            .method(PluginMethods.ESTIMATE, EstimateInput.class, EstimateOutput.class,
                (EstimateInput in, RpcContext ctx) -> handlers.estimate(in, ctx))
            .method(PluginMethods.SPLIT, SplitInput.class, SplitOutput.class,
                (SplitInput in, RpcContext ctx) -> handlers.split(in, ctx))
            // UI-facing async split for large workbooks (start → jobId → poll status → cancel).
            .method(PluginMethods.SPLIT_START, SplitStartInput.class, SplitStartOutput.class,
                (SplitStartInput in, RpcContext ctx) -> handlers.splitStart(in, ctx))
            .method(PluginMethods.SPLIT_STATUS, SplitStatusInput.class, SplitStatusOutput.class,
                (SplitStatusInput in, RpcContext ctx) -> handlers.splitStatus(in, ctx))
            .method(PluginMethods.SPLIT_CANCEL, SplitCancelInput.class, SplitCancelOutput.class,
                (SplitCancelInput in, RpcContext ctx) -> handlers.splitCancel(in, ctx))
            // AI-facing, stateless tools (declared in manifest.aiTools[]).
            .method(PluginMethods.EXCEL_ANALYZE, ExcelAnalyzeInput.class, ExcelAnalyzeOutput.class,
                (ExcelAnalyzeInput in, RpcContext ctx) -> handlers.aiAnalyze(in, ctx))
            .method(PluginMethods.EXCEL_CONFIGURE, ExcelConfigureInput.class, ExcelConfigureOutput.class,
                (ExcelConfigureInput in, RpcContext ctx) -> handlers.aiConfigure(in, ctx))
            .method(PluginMethods.EXCEL_COMPLEX_CONFIG, ExcelComplexConfigInput.class, ExcelComplexConfigOutput.class,
                (ExcelComplexConfigInput in, RpcContext ctx) -> handlers.aiComplexConfig(in, ctx))
            .method(PluginMethods.EXCEL_EXECUTE, ExcelExecuteInput.class, ExcelExecuteOutput.class,
                (ExcelExecuteInput in, RpcContext ctx) -> handlers.aiExecute(in, ctx))
            .method(PluginMethods.EXCEL_EXECUTE_START, ExcelExecuteStartInput.class, ExcelExecuteStartOutput.class,
                (ExcelExecuteStartInput in, RpcContext ctx) -> handlers.aiExecuteStart(in, ctx))
            .method(PluginMethods.EXCEL_EXECUTE_STATUS, ExcelExecuteStatusInput.class, ExcelExecuteStatusOutput.class,
                (ExcelExecuteStatusInput in, RpcContext ctx) -> handlers.aiExecuteStatus(in, ctx))
            .method(PluginMethods.EXCEL_QUERY, ExcelQueryInput.class, ExcelQueryOutput.class,
                (ExcelQueryInput in, RpcContext ctx) -> handlers.aiQuery(in, ctx))
            .method(PluginMethods.EXCEL_CANCEL, ExcelCancelInput.class, ExcelCancelOutput.class,
                (ExcelCancelInput in, RpcContext ctx) -> handlers.aiCancel(in, ctx));
    }
}
