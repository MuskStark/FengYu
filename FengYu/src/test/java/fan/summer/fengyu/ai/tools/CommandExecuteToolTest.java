package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.security.ProcessSandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertEquals(tempDir.toRealPath().toString(),
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

    @Test
    void destroysProcessAndClosesJobHandleWhenOnStartedThrowsAfterAssignment() throws Exception {
        HookFailureSandbox failing = new HookFailureSandbox(true);
        CommandExecuteTool commandTool = new CommandExecuteTool(failing);

        JsonNode result = JSON.readTree(commandTool.execute(
                "sleep 30", tempDir.toString(), 5, 1024));

        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("hook failed"));
        assertEquals(1, failing.terminateCalls.get());
        assertEquals(1, failing.closeCalls.get());
        assertFalse(failing.startedProcess.isAlive());
    }

    @Test
    void closesJobHandleAfterSuccessfulCommand() throws Exception {
        HookFailureSandbox sandbox = new HookFailureSandbox(false);
        CommandExecuteTool commandTool = new CommandExecuteTool(sandbox);

        JsonNode result = JSON.readTree(commandTool.execute(
                "printf done", tempDir.toString(), 5, 1024));

        assertTrue(result.path("success").asBoolean());
        assertEquals(0, sandbox.terminateCalls.get());
        assertEquals(1, sandbox.closeCalls.get());
    }

    @Test
    void destroysProcessAndClosesJobHandleWhenOnStartedThrowsError() {
        HookFailureSandbox failing = new HookFailureSandbox(false, true);
        CommandExecuteTool commandTool = new CommandExecuteTool(failing);

        assertThrows(AssertionError.class, () -> commandTool.execute(
                "sleep 30", tempDir.toString(), 5, 1024));

        assertEquals(1, failing.terminateCalls.get());
        assertEquals(1, failing.closeCalls.get());
        assertFalse(failing.startedProcess.isAlive());
    }

    private static final class HookFailureSandbox extends ProcessSandbox {
        private static final long HANDLE = 4242L;
        private final boolean failHook;
        private final boolean failWithError;
        private final AtomicInteger terminateCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private volatile Process startedProcess;

        private HookFailureSandbox(boolean failHook) {
            this(failHook, false);
        }

        private HookFailureSandbox(boolean failHook, boolean failWithError) {
            super(Backend.NONE);
            this.failHook = failHook;
            this.failWithError = failWithError;
        }

        @Override
        public Launch command(List<String> raw, Path workingDirectory, boolean allowNetwork) {
            return new Launch(raw, Backend.WINDOWS_JOB, (process, handleOut) -> {
                startedProcess = process;
                handleOut[0] = HANDLE;
                if (failWithError) throw new AssertionError("hook error after assignment");
                if (failHook) throw new IllegalStateException("hook failed after assignment");
            });
        }

        @Override
        public void terminateJob(long jobHandle) {
            assertEquals(HANDLE, jobHandle);
            terminateCalls.incrementAndGet();
        }

        @Override
        public void closeJobHandle(long jobHandle) {
            assertEquals(HANDLE, jobHandle);
            closeCalls.incrementAndGet();
        }
    }
}
