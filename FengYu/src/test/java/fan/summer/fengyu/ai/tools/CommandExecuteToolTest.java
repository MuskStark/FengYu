package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.security.ProcessSandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandExecuteToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final CommandExecuteTool tool =
            new CommandExecuteTool(new ProcessSandbox(ProcessSandbox.Backend.NONE));

    @TempDir
    Path tempDir;

    @Test
    void executesCommandInRequestedWorkingDirectory() throws Exception {
        JsonNode result = JSON.readTree(tool.execute(
                "printf 'hello'; printf ' error' >&2", tempDir.toString(), 5, 1024));

        assertTrue(result.path("success").asBoolean());
        assertEquals(0, result.path("exitCode").asInt());
        assertEquals("hello error", result.path("output").asText());
        assertEquals(tempDir.toAbsolutePath().normalize().toString(),
                result.path("workingDirectory").asText());
        assertFalse(result.path("timedOut").asBoolean());
        assertFalse(result.path("truncated").asBoolean());
        assertFalse(result.path("sandboxed").asBoolean());
        assertEquals("none", result.path("sandboxBackend").asText());
        assertFalse(result.path("networkAllowed").asBoolean());
    }

    @Test
    void reportsTimeoutAndTerminatesProcess() throws Exception {
        JsonNode result = JSON.readTree(tool.execute("sleep 5", tempDir.toString(), 1, 1024));

        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("timedOut").asBoolean());
        assertNull(result.get("exitCode").textValue());
    }

    @Test
    void truncatesCapturedOutputWithoutBlockingProcess() throws Exception {
        JsonNode result = JSON.readTree(tool.execute(
                "printf '1234567890'", tempDir.toString(), 5, 4));

        assertTrue(result.path("success").asBoolean());
        assertEquals("1234", result.path("output").asText());
        assertTrue(result.path("truncated").asBoolean());
    }

    @Test
    void rejectsMissingWorkingDirectory() throws Exception {
        JsonNode result = JSON.readTree(tool.execute(
                "printf ignored", tempDir.resolve("missing").toString(), 5, 1024));

        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("does not exist"));
    }

    @Test
    void recognizesSensitiveEnvironmentNames() {
        assertTrue(CommandExecuteTool.isSensitiveEnvironmentName("OPENAI_API_KEY"));
        assertTrue(CommandExecuteTool.isSensitiveEnvironmentName("github_token"));
        assertTrue(CommandExecuteTool.isSensitiveEnvironmentName("DB_PASSWORD"));
        assertFalse(CommandExecuteTool.isSensitiveEnvironmentName("PATH"));
        assertFalse(CommandExecuteTool.isSensitiveEnvironmentName("JAVA_HOME"));
    }
}
