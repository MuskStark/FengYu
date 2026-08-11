package fan.summer.fengyu.plugin.excel;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces the official SDK worker pattern for the Toolchain 2 typed model: the excel package
 * registers its RPC methods through {@code JsonRpcWorker#method(...)} with the generated
 * {@code PluginMethods} constants + {@code *Input}/{@code *Output} DTOs, never hand-rolling its own
 * RPC loop, shadowing SDK protocol types, extending {@code PluginHandlerSupport}, or using the
 * removed {@code .on("...")} registration / {@code JsonRpcWorker.string}/{@code .integer} helpers.
 */
class OfficialSdkUsageTest {
    private static final Path SOURCE = Path.of("src/main/java/fan/summer/fengyu/plugin/excel");

    @Test
    void workerUsesTypedMethodRegistration() throws IOException {
        String allSource = readJavaSources();
        Pattern shadows = Pattern.compile("\\b(?:class|record|interface)\\s+(?:JsonRpcWorker|PluginHandler|RpcContext)\\b");

        assertFalse(shadows.matcher(allSource).find(), "excel package must not shadow official SDK types");
        assertFalse(allSource.contains("readLine()"), "excel package must not implement a JSON-RPC loop");
        assertFalse(allSource.contains("JsonRpcWorker.string"),
                "excel package must not use the removed JsonRpcWorker.string param helper");
        assertFalse(allSource.contains("JsonRpcWorker.integer"),
                "excel package must not use the removed JsonRpcWorker.integer param helper");
        assertFalse(allSource.contains(".on(\""),
                "excel package must not use the removed .on(\"...\") registration");
        assertFalse(allSource.contains("PluginHandlerSupport"),
                "excel package must not extend or reference the legacy PluginHandlerSupport base class");
        assertTrue(allSource.contains("import fan.summer.fengyu.sdk.JsonRpcWorker;"),
                "excel package must use the official SDK worker runtime");
        assertTrue(allSource.contains("import fan.summer.fengyu.sdk.RpcContext;"),
                "excel package must receive the per-call RpcContext");
        assertTrue(allSource.contains("import fan.summer.excel.generated."),
                "excel package must bind the generated DTO package");
        assertTrue(allSource.contains(".method("),
                "excel package must register handlers via the typed worker.method(...) API");
    }

    @Test
    void workerRegistersAllDeclaredMethodsViaPluginMethodsConstants() throws IOException {
        String source = Files.readString(SOURCE.resolve("ExcelWorkerMain.java"));
        for (String constant : new String[]{
                "ANALYZE", "CONFIGURE", "ESTIMATE", "SPLIT",
                "SPLIT_START", "SPLIT_STATUS", "SPLIT_CANCEL",
                "EXCEL_ANALYZE", "EXCEL_CONFIGURE", "EXCEL_COMPLEX_CONFIG",
                "EXCEL_EXECUTE", "EXCEL_EXECUTE_START", "EXCEL_EXECUTE_STATUS",
                "EXCEL_QUERY", "EXCEL_CANCEL"}) {
            assertTrue(source.contains("PluginMethods." + constant),
                    "ExcelWorkerMain must register method via PluginMethods." + constant);
        }
    }

    private static String readJavaSources() throws IOException {
        try (var paths = Files.walk(SOURCE)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                .sorted().map(OfficialSdkUsageTest::read).reduce("", (left, right) -> left + "\n" + right);
        }
    }

    private static String read(Path path) {
        try { return Files.readString(path); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }
}
