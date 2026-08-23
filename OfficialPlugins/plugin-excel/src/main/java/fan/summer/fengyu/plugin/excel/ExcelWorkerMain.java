package fan.summer.fengyu.plugin.excel;

import fan.summer.fengyu.sdk.JsonRpcWorker;
import fan.summer.fengyu.sdk.RpcContext;
import fan.summer.excel.contract.ExcelContract.AnalyzeInput;
import fan.summer.excel.contract.ExcelContract.AnalyzeOutput;
import fan.summer.excel.contract.ExcelContract.ConfigureInput;
import fan.summer.excel.contract.ExcelContract.ConfigureOutput;
import fan.summer.excel.contract.ExcelContract.EstimateInput;
import fan.summer.excel.contract.ExcelContract.EstimateOutput;
import fan.summer.excel.contract.ExcelContract.ExcelAnalyzeInput;
import fan.summer.excel.contract.ExcelContract.ExcelAnalyzeOutput;
import fan.summer.excel.contract.ExcelContract.ExcelCancelInput;
import fan.summer.excel.contract.ExcelContract.ExcelCancelOutput;
import fan.summer.excel.contract.ExcelContract.ExcelComplexConfigInput;
import fan.summer.excel.contract.ExcelContract.ExcelComplexConfigOutput;
import fan.summer.excel.contract.ExcelContract.ExcelConfigureInput;
import fan.summer.excel.contract.ExcelContract.ExcelConfigureOutput;
import fan.summer.excel.contract.ExcelContract.ExcelExecuteInput;
import fan.summer.excel.contract.ExcelContract.ExcelExecuteOutput;
import fan.summer.excel.contract.ExcelContract.ExcelExecuteStartInput;
import fan.summer.excel.contract.ExcelContract.ExcelExecuteStartOutput;
import fan.summer.excel.contract.ExcelContract.ExcelExecuteStatusInput;
import fan.summer.excel.contract.ExcelContract.ExcelExecuteStatusOutput;
import fan.summer.excel.contract.ExcelContract.ExcelQueryInput;
import fan.summer.excel.contract.ExcelContract.ExcelQueryOutput;
import fan.summer.excel.generated.PluginMethods;
import fan.summer.excel.contract.ExcelContract.SplitCancelInput;
import fan.summer.excel.contract.ExcelContract.SplitCancelOutput;
import fan.summer.excel.contract.ExcelContract.SplitInput;
import fan.summer.excel.contract.ExcelContract.SplitOutput;
import fan.summer.excel.contract.ExcelContract.SplitStartInput;
import fan.summer.excel.contract.ExcelContract.SplitStartOutput;
import fan.summer.excel.contract.ExcelContract.SplitStatusInput;
import fan.summer.excel.contract.ExcelContract.SplitStatusOutput;

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
