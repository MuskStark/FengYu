package fan.summer.fengyu.account;

import fan.summer.fengyu.account.OsCloudSecretStore.Backend;
import fan.summer.fengyu.account.OsCloudSecretStore.CommandRunner;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Command-shape regression for the OS credential store (review M-5): the
 * macOS / Linux / Windows backends must talk to the native facility, and a
 * host without one must refuse rather than persist anywhere weaker.
 */
class OsCloudSecretStoreTest {

    private static final class RecordingRunner implements CommandRunner {
        final List<List<String>> commands = new ArrayList<>();
        final List<String> stdins = new ArrayList<>();
        String stdout = "";
        int failAfter = Integer.MAX_VALUE;
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String run(List<String> command, String stdin) throws IOException {
            commands.add(List.copyOf(command));
            stdins.add(stdin);
            if (calls.incrementAndGet() > failAfter) {
                throw new IOException("exit 1");
            }
            return stdout;
        }
    }

    @Test
    void macOSBackendUsesTheKeychainCLI() throws Exception {
        RecordingRunner runner = new RecordingRunner();
        OsCloudSecretStore store = new OsCloudSecretStore(Backend.MACOS_KEYCHAIN, runner);

        assertTrue(store.available());
        store.save("fengyu.cloud.refresh-token", "secret-value");

        List<String> command = runner.commands.get(0);
        assertEquals("security", command.get(0));
        assertTrue(command.contains("add-generic-password"));
        assertTrue(command.contains("-s"), "item service name on the command line");
        assertTrue(command.contains("secret-value"));
        assertTrue(command.contains("-U"), "-U replaces an existing item");

        runner.stdout = "secret-value\n";
        assertEquals(Optional.of("secret-value"), store.load("fengyu.cloud.refresh-token"));
        assertTrue(runner.commands.get(1).contains("find-generic-password"));

        // A keychain miss (non-zero exit) is an empty Optional, not a failure.
        runner.failAfter = 1;
        assertEquals(Optional.empty(), store.load("fengyu.cloud.refresh-token"));

        store.delete("fengyu.cloud.refresh-token");
        assertTrue(runner.commands.get(3).contains("delete-generic-password"),
                "actual commands: " + runner.commands);
    }

    @Test
    void linuxBackendUsesSecretToolWithSecretOnStdin() throws Exception {
        RecordingRunner runner = new RecordingRunner();
        OsCloudSecretStore store = new OsCloudSecretStore(Backend.LINUX_SECRET_SERVICE,
                runner);

        store.save("fengyu.cloud.refresh-token", "secret-value");

        List<String> command = runner.commands.get(0);
        assertEquals("secret-tool", command.get(0));
        assertTrue(command.contains("store"));
        assertEquals("secret-value", runner.stdins.get(0),
                "the secret must travel over stdin, not argv");
        assertTrue(command.contains("fengyu.cloud.refresh-token"));
    }

    @Test
    void windowsBackendUsesPowerShellWithBase64Blob() throws Exception {
        RecordingRunner runner = new RecordingRunner();
        OsCloudSecretStore store = new OsCloudSecretStore(
                Backend.WINDOWS_CREDENTIAL_MANAGER, runner);

        store.save("fengyu.cloud.refresh-token", "secret-value");

        List<String> command = runner.commands.get(0);
        assertEquals("powershell", command.get(0));
        String script = command.get(command.size() - 1);
        assertTrue(script.contains("CredWriteW"), "writes via Credential Manager");
        assertEquals("c2VjcmV0LXZhbHVl", runner.stdins.get(0),
                "the blob travels as base64 over stdin");

        runner.stdout = "c2VjcmV0LXZhbHVl\n";
        assertEquals(Optional.of("secret-value"), store.load("fengyu.cloud.refresh-token"));
        assertTrue(runner.commands.get(1).toString().contains("CredReadW"));
    }

    @Test
    void hostWithoutACredentialStoreRefusesInsteadOfPersisting() {
        OsCloudSecretStore store = new OsCloudSecretStore(Backend.NONE,
                new RecordingRunner());

        assertFalse(store.available());
        assertThrows(IllegalStateException.class,
                () -> store.save("fengyu.cloud.refresh-token", "secret-value"));
        assertThrows(IllegalStateException.class, () -> store.load("name"));
        assertThrows(IllegalStateException.class, () -> store.delete("name"));
    }
}
