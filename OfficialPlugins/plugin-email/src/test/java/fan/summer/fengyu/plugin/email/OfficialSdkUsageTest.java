package fan.summer.fengyu.plugin.email;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that the Email worker uses the official SDK under the Toolchain 2 typed contract:
 * methods are registered via {@code JsonRpcWorker.method(...)} against generated {@code PluginMethods}
 * constants and typed Input records, handlers receive an {@code RpcContext}, and the package never
 * shadows SDK types or hand-rolls the JSON-RPC loop.
 */
class OfficialSdkUsageTest {
    private static final Path SOURCE = Path.of("src/main/java/fan/summer/fengyu/plugin/email");

    @Test void workerUsesOnlyOfficialSDKProtocolAndCapabilityTypes() throws IOException {
        String allSource = readJavaSources();
        Pattern shadows = Pattern.compile("\\b(?:class|record|interface)\\s+(?:JsonRpcWorker|FileRef)\\b");

        assertFalse(shadows.matcher(allSource).find(), "email package must not shadow official SDK types");
        assertFalse(allSource.contains("readLine()"), "email package must not implement a JSON-RPC loop");
        // Typed Toolchain 2 contract: the worker registers every method through the typed
        // JsonRpcWorker.method(...) API and binds a per-call RpcContext (no string-based .on(...)
        // registration, no raw Map parsing).
        assertTrue(allSource.contains("import fan.summer.fengyu.sdk.JsonRpcWorker;"));
        assertTrue(allSource.contains(".method("),
            "email package must register methods via JsonRpcWorker.method(...)");
        assertTrue(allSource.contains("import fan.summer.fengyu.sdk.RpcContext;"),
            "email handlers must bind an RpcContext");
        assertTrue(allSource.contains("import fan.summer.fengyu.sdk.PluginDatabaseConfig;"));
    }

    @Test void workerRegistersTheExactAiAndConfirmationMethods() throws IOException {
        String source = Files.readString(SOURCE.resolve("EmailWorkerMain.java"));
        // Each rpc method is registered through its generated PluginMethods constant (the constant
        // name is the method name uppercased), not a free-standing string literal.
        List<String> methods = List.of("email_accounts_list", "email_contacts_query", "email_send_single",
            "email_send_batch", "email_send_status", "email_archive_fetch", "email_archive_query",
            "confirm_send", "reject_send");
        methods.forEach(method -> assertTrue(
            source.contains("PluginMethods." + method.toUpperCase()),
            method + " must be registered via its PluginMethods constant"));
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
