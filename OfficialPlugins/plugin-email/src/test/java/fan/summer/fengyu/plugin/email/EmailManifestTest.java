package fan.summer.fengyu.plugin.email;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailManifestTest {
    private static final Gson JSON = new Gson();
        // Code-first: the authoritative manifest is the compiled merge in build output
    // (target/fengyu-manifest), produced by `fengyu generate|check|build` before tests
    // run. A bare `mvn test` without that step has nothing to assert against — skip
    // with instructions instead of failing (release CI always goes through the CLI).
    private static final Path MANIFEST = Path.of("target/fengyu-manifest/manifest.json");

    @Test void officialManifestMatchesWorkerAndPackageContract() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(MANIFEST),
                "compiled manifest is absent — run `fengyu generate` (or check/build) first");
        JsonObject manifest = JSON.fromJson(Files.readString(MANIFEST), JsonObject.class);
        assertEquals("fan.summer.email", manifest.get("id").getAsString());
        assertTrue(manifest.get("version").getAsString().matches("\\d+\\.\\d+\\.\\d+(-(alpha|beta|rc)\\.\\d+)?"));
        assertTrue(manifest.get("official").getAsBoolean());
        assertEquals("ui/index.html", manifest.getAsJsonObject("ui").get("entry").getAsString());
        assertEquals(60, manifest.getAsJsonObject("backend").get("callTimeoutSeconds").getAsInt());
        assertTrue(Files.isRegularFile(Path.of("ui-src/index.html")));
        assertTrue(Files.isRegularFile(Path.of("src/main/java/fan/summer/fengyu/plugin/email/EmailWorkerMain.java")));
        // `notifications`: the UI surfaces send results via the host notify bridge, so the
        // plugin declares the permission that gates the unified host notification surface.
        assertEquals(List.of("database", "network.email", "files.read", "files.write", "notifications"),
            manifest.getAsJsonArray("permissions").asList().stream().map(value -> value.getAsString()).toList());

        List<String> expected = List.of("email_accounts_list", "email_contacts_query", "email_send_single",
            "email_send_batch", "email_send_status", "email_archive_fetch", "email_archive_query",
            "email_account_test", "email_account_test_imap", "confirm_send");
        var tools = manifest.getAsJsonArray("aiTools").asList();
        assertEquals(expected.size(), tools.size());
        var names = tools.stream().map(value -> value.getAsJsonObject().get("name").getAsString()).sorted().toList();
        var methods = tools.stream().map(value -> value.getAsJsonObject().get("method").getAsString()).sorted().toList();
        // The compiled manifest sorts aiTools by name deterministically.
        assertEquals(expected.stream().sorted().toList(), names);
        assertEquals(expected.stream().sorted().toList(), methods);
        assertEquals(names.size(), new HashSet<>(names).size());
        // confirm_send stays human-gated: its workflow/chat step is an external-effect tool,
        // so every permission mode except full-access pauses for an explicit approval.
        assertTrue(tools.stream().anyMatch(value -> "confirm_send"
            .equals(value.getAsJsonObject().get("name").getAsString())
            && "external".equals(value.getAsJsonObject().get("effect").getAsString())));
        // Preparing a send persists a single-use pending snapshot. It is a non-idempotent
        // write (not a read), so Flow must never auto-retry it into duplicate confirmations.
        for (String prepare : List.of("email_send_single", "email_send_batch")) {
            JsonObject value = tools.stream().map(item -> item.getAsJsonObject())
                .filter(item -> prepare.equals(item.get("name").getAsString()))
                .findFirst().orElseThrow();
            assertEquals("write", value.get("effect").getAsString());
            assertTrue(!value.has("idempotent") || !value.get("idempotent").getAsBoolean());
        }
        // v2 manifest: each aiTool references a method by name; the input/output schemas live as
        // inline objects under rpc.methods.<method> (v1 embedded inputSchema as a JSON string in
        // each aiTool entry).
        JsonObject rpcMethods = manifest.getAsJsonObject("rpc").getAsJsonObject("methods");
        for (var value : tools) {
            String method = value.getAsJsonObject().get("method").getAsString();
            JsonObject schema = rpcMethods.getAsJsonObject(method).getAsJsonObject("inputSchema");
            assertEquals("object", schema.get("type").getAsString(), method + " inputSchema.type");
        }
        assertTrue(tools.stream().anyMatch(value -> value.getAsJsonObject().get("description")
            .getAsString().contains("does not send")));
        assertTrue(tools.stream().anyMatch(value -> value.getAsJsonObject().get("description")
            .getAsString().contains("without sending")));
        JsonObject batchSchema = rpcMethods.getAsJsonObject("email_send_batch").getAsJsonObject("inputSchema");
        assertEquals(List.of("accountId", "ccGroupTagIds", "inputDirectory", "recipientGroupTagIds"),
            batchSchema.getAsJsonArray("required").asList().stream().map(value -> value.getAsString()).sorted().toList());
        assertTrue(batchSchema.getAsJsonObject("properties").has("commonAttachments"));
        assertTrue(!batchSchema.getAsJsonObject("properties").has("mode"));
        assertTrue(!Files.readString(MANIFEST).contains("email_send_retry"));
    }
}
