package fan.summer.fengyu.plugin.excel;

import fan.summer.fengyu.plugin.excel.ai.*;
import fan.summer.fengyu.sdk.JsonRpcWorker;
import java.util.List;

public final class ExcelWorkerMain {
    private ExcelWorkerMain() {}
    public static void main(String[] args) throws Exception {
        worker().run();
    }

    static JsonRpcWorker worker() {
        ExcelSessionStore sessions = new ExcelSessionStore();
        ExcelPlugin plugin = new ExcelPlugin(sessions);
        ExcelAnalyzeTool analyze = new ExcelAnalyzeTool(sessions);
        ExcelConfigureTool configure = new ExcelConfigureTool(sessions);
        ExcelComplexConfigTool complex = new ExcelComplexConfigTool(sessions);
        ExcelExecuteTool execute = new ExcelExecuteTool(sessions);
        ExcelQueryTool query = new ExcelQueryTool(sessions);
        ExcelCancelTool cancel = new ExcelCancelTool(sessions);
        return new JsonRpcWorker()
            .on("analyze", p -> plugin.invoke("analyze", p)).on("configure", p -> plugin.invoke("configure", p)).on("split", p -> plugin.invoke("split", p))
            .on("excel_analyze", p -> analyze.analyze(JsonRpcWorker.string(p, "filePath")))
            .on("excel_configure", p -> configure.configure(JsonRpcWorker.string(p, "mode"), castList(p.get("sheets")), JsonRpcWorker.string(p, "splitSheet"), JsonRpcWorker.string(p, "splitColumn")))
            .on("excel_complex_config", p -> complex.complexConfig(JsonRpcWorker.string(p, "action"), JsonRpcWorker.string(p, "sheetName"), JsonRpcWorker.integer(p, "headerIndex", -1), JsonRpcWorker.integer(p, "columnIndex", -1)))
            .on("excel_execute", p -> execute.execute(JsonRpcWorker.string(p, "outputDir"), JsonRpcWorker.string(p, "filePrefix")))
            .on("excel_query", p -> query.query()).on("excel_cancel", p -> cancel.cancel());
    }
    @SuppressWarnings("unchecked") private static List<String> castList(Object value) { return value instanceof List<?> list ? (List<String>) list : null; }
}
