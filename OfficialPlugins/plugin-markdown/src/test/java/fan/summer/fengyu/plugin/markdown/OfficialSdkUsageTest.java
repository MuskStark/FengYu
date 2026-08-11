package fan.summer.fengyu.plugin.markdown;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces the official SDK worker pattern for the Toolchain 2 typed model: the markdown package
 * registers its RPC method through {@code JsonRpcWorker#method(...)} with the generated
 * {@code PluginMethods} constants + {@code RenderInput}/{@code RenderOutput} DTOs, never
 * hand-rolling its own RPC loop, shadowing SDK protocol types, or using the removed
 * {@code JsonRpcWorker.string}/{@code .integer} param helpers.
 */
class OfficialSdkUsageTest {
    private static final Path SOURCE = Path.of("src/main/java/fan/summer/fengyu/plugin/markdown");

    @Test
    void workerUsesTypedMethodRegistration() throws IOException {
        String allSource = readJavaSources();
        Pattern shadows = Pattern.compile("\\b(?:class|record|interface)\\s+(?:JsonRpcWorker|PluginHandler|RpcContext)\\b");

        assertFalse(shadows.matcher(allSource).find(), "markdown package must not shadow official SDK types");
        assertFalse(allSource.contains("readLine()"), "markdown package must not implement a JSON-RPC loop");
        assertFalse(allSource.contains("JsonRpcWorker.string"),
                "markdown package must not use the removed JsonRpcWorker.string param helper");
        assertFalse(allSource.contains("JsonRpcWorker.integer"),
                "markdown package must not use the removed JsonRpcWorker.integer param helper");
        assertTrue(allSource.contains("import fan.summer.fengyu.sdk.JsonRpcWorker;"),
                "markdown package must use the official SDK worker runtime");
        assertTrue(allSource.contains("import fan.summer.markdown.generated.PluginMethods;"),
                "markdown package must register methods via the generated PluginMethods constants");
        assertTrue(allSource.contains(".method("),
                "markdown package must register handlers via the typed worker.method(...) API");
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
