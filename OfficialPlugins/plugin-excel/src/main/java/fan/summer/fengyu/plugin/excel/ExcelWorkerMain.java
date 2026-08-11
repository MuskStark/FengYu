package fan.summer.fengyu.plugin.excel;

import fan.summer.fengyu.sdk.JsonRpcWorker;

/**
 * Excel Splitter worker. Speaks newline-delimited JSON-RPC 2.0 on stdio. Methods are split into
 * the session-keyed UI workflow ({@code analyze}/{@code configure}/{@code estimate}/{@code split})
 * and the stateless AI tools ({@code excel_*}) declared in {@code manifest.json}; both share one
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
            .onClose(handlers)
            // UI-facing, session-keyed workflow.
            .on("analyze", handlers.handle("analyze", handlers::analyze))
            .on("configure", handlers.handle("configure", handlers::configure))
            .on("estimate", handlers.handle("estimate", handlers::estimate))
            .on("split", handlers.handle("split", handlers::split))
            // UI-facing async split for large workbooks (start → jobId → poll status → cancel).
            .on("split_start", handlers.handle("split_start", handlers::splitStart))
            .on("split_status", handlers.handle("split_status", handlers::splitStatus))
            .on("split_cancel", handlers.handle("split_cancel", handlers::splitCancel))
            // AI-facing, stateless tools (declared in manifest.aiTools[]).
            .on("excel_analyze", handlers.handle("excel_analyze", handlers::aiAnalyze))
            .on("excel_configure", handlers.handle("excel_configure", handlers::aiConfigure))
            .on("excel_complex_config", handlers.handle("excel_complex_config", handlers::aiComplexConfig))
            .on("excel_execute", handlers.handle("excel_execute", handlers::aiExecute))
            .on("excel_execute_start", handlers.handle("excel_execute_start", handlers::aiExecuteStart))
            .on("excel_execute_status", handlers.handle("excel_execute_status", handlers::aiExecuteStatus))
            .on("excel_query", handlers.handle("excel_query", handlers::aiQuery))
            .on("excel_cancel", handlers.handle("excel_cancel", handlers::aiCancel));
    }
}
