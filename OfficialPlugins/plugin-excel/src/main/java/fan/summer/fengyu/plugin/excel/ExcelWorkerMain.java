package fan.summer.fengyu.plugin.excel;

import fan.summer.fengyu.sdk.JsonRpcWorker;

/**
 * Excel Splitter worker. Speaks newline-delimited JSON-RPC 2.0 on stdio. Methods are split into
 * the session-keyed UI workflow ({@code analyze}/{@code configure}/{@code split}) and the
 * stateless AI tools ({@code excel_*}) declared in {@code manifest.json}; both share one
 * {@link ExcelRpcHandlers}. {@link JsonRpcWorker#run()} redirects stdout to stderr so the
 * protocol stream on stdout stays clean.
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
            // UI-facing, session-keyed workflow.
            .on("analyze", handlers.safe(handlers::analyze))
            .on("configure", handlers.safe(handlers::configure))
            .on("split", handlers.safe(handlers::split))
            // UI-facing async split for large workbooks (start → jobId → poll status → cancel).
            .on("split_start", handlers.safe(handlers::splitStart))
            .on("split_status", handlers.safe(handlers::splitStatus))
            .on("split_cancel", handlers.safe(handlers::splitCancel))
            // AI-facing, stateless tools (declared in manifest.aiTools[]).
            .on("excel_analyze", handlers.safe(handlers::aiAnalyze))
            .on("excel_configure", handlers.safe(handlers::aiConfigure))
            .on("excel_complex_config", handlers.safe(handlers::aiComplexConfig))
            .on("excel_execute", handlers.safe(handlers::aiExecute))
            .on("excel_execute_start", handlers.safe(handlers::aiExecuteStart))
            .on("excel_execute_status", handlers.safe(handlers::aiExecuteStatus))
            .on("excel_query", handlers.safe(handlers::aiQuery))
            .on("excel_cancel", handlers.safe(handlers::aiCancel));
    }
}
