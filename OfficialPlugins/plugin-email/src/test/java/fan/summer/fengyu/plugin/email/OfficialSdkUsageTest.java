package fan.summer.fengyu.plugin.email;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficialSdkUsageTest {
    private static final Path SOURCE = Path.of("src/main/java/fan/summer/fengyu/plugin/email");

    @Test void workerUsesOnlyOfficialSdkProtocolAndCapabilityTypes() throws IOException {
        String allSource = readJavaSources();
        Pattern shadows = Pattern.compile("\\b(?:class|record|interface)\\s+(?:JsonRpcWorker|FileRef)\\b");

        assertFalse(shadows.matcher(allSource).find(), "email package must not shadow official SDK types");
        assertFalse(allSource.contains("readLine()"), "email package must not implement a JSON-RPC loop");
        assertTrue(allSource.contains("import fan.summer.fengyu.sdk.JsonRpcWorker;"));
        assertTrue(allSource.contains("import fan.summer.fengyu.sdk.PluginHandler;"));
        assertTrue(allSource.contains("import fan.summer.fengyu.sdk.FileRef;"));
        assertTrue(allSource.contains("import fan.summer.fengyu.sdk.PluginDatabaseConfig;"));
    }

    @Test void workerRegistersTheExactAiAndConfirmationMethods() throws IOException {
        String source = Files.readString(SOURCE.resolve("EmailWorkerMain.java"));
        List<String> methods = List.of("email_accounts_list", "email_contacts_query", "email_send_single",
            "email_send_batch", "email_send_status", "email_archive_fetch", "email_archive_query",
            "confirm_send", "reject_send");
        methods.forEach(method -> assertTrue(source.contains("\"" + method + "\""), method));
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
