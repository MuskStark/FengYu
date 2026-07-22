package fan.summer.fengyu.plugin.markdown;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces the official SDK worker pattern: the plugin speaks JSON-RPC 2.0 through
 * {@code JsonRpcWorker} + {@code PluginHandler}, never hand-rolling its own RPC loop
 * or shadowing the SDK protocol types.
 */
class OfficialSdkUsageTest {
    private static final Path SOURCE = Path.of("src/main/java/fan/summer/fengyu/plugin/markdown");

    @Test
    void workerUsesOnlyOfficialSdkProtocolTypes() throws IOException {
        String allSource = readJavaSources();
        Pattern shadows = Pattern.compile("\\b(?:class|record|interface)\\s+(?:JsonRpcWorker|PluginHandler)\\b");

        assertFalse(shadows.matcher(allSource).find(), "markdown package must not shadow official SDK types");
        assertFalse(allSource.contains("readLine()"), "markdown package must not implement a JSON-RPC loop");
        assertTrue(allSource.contains("import fan.summer.fengyu.sdk.JsonRpcWorker;"));
        // Handlers use the SDK: either the legacy PluginHandler import or the newer
        // PluginHandlerSupport base class (which lives in the same package).
        assertTrue(allSource.contains("import fan.summer.fengyu.sdk.PluginHandler;")
                || allSource.contains("import fan.summer.fengyu.sdk.PluginHandlerSupport;"),
                "markdown package must use an official SDK handler type");
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
