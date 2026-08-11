package fan.summer.fengyu.plugin.email;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailManifestTest {
    private static final Gson JSON = new Gson();
    private static final Path MANIFEST = Path.of("manifest.json");

    @Test void officialManifestMatchesWorkerAndPackageContract() throws Exception {
        assertTrue(Files.isRegularFile(MANIFEST));
        JsonObject manifest = JSON.fromJson(Files.readString(MANIFEST), JsonObject.class);
        assertEquals("fan.summer.email", manifest.get("id").getAsString());
        assertTrue(manifest.get("version").getAsString().matches("\\d+\\.\\d+\\.\\d+(-(alpha|beta|rc)\\.\\d+)?"));
        assertTrue(manifest.get("official").getAsBoolean());
        assertEquals("ui/index.html", manifest.getAsJsonObject("ui").get("entry").getAsString());
        assertEquals(60, manifest.getAsJsonObject("backend").get("callTimeoutSeconds").getAsInt());
        assertTrue(Files.isRegularFile(Path.of("ui-src/index.html")));
        assertTrue(Files.isRegularFile(Path.of("src/main/java/fan/summer/fengyu/plugin/email/EmailWorkerMain.java")));
        assertEquals(List.of("database", "network.email", "files.read", "files.write"),
            manifest.getAsJsonArray("permissions").asList().stream().map(value -> value.getAsString()).toList());

        List<String> expected = List.of("email_accounts_list", "email_contacts_query", "email_send_single",
            "email_send_batch", "email_send_status", "email_archive_fetch", "email_archive_query",
            "email_account_test", "email_account_test_imap");
        var tools = manifest.getAsJsonArray("aiTools").asList();
        assertEquals(expected.size(), tools.size());
        var names = tools.stream().map(value -> value.getAsJsonObject().get("name").getAsString()).toList();
        var methods = tools.stream().map(value -> value.getAsJsonObject().get("method").getAsString()).toList();
        assertEquals(expected, names);
        assertEquals(expected, methods);
        assertEquals(names.size(), new HashSet<>(names).size());
        // v2 manifest: each aiTool references a method by name; the input/output schemas live as
        // inline objects under rpc.methods.<method> (v1 embedded inputSchema as a JSON string in
        // each aiTool entry).
        JsonObject rpcMethods = manifest.getAsJsonObject("rpc").getAsJsonObject("methods");
        for (var value : tools) {
            String method = value.getAsJsonObject().get("method").getAsString();
            JsonObject schema = rpcMethods.getAsJsonObject(method).getAsJsonObject("inputSchema");
            assertEquals("object", schema.get("type").getAsString(), method + " inputSchema.type");
        }
        assertTrue(tools.get(2).getAsJsonObject().get("description").getAsString().contains("does not send"));
        assertTrue(tools.get(3).getAsJsonObject().get("description").getAsString().contains("without sending"));
        JsonObject batchSchema = rpcMethods.getAsJsonObject("email_send_batch").getAsJsonObject("inputSchema");
        assertEquals(List.of("accountId", "recipientGroupTagIds", "ccGroupTagIds", "inputDirectory"),
            batchSchema.getAsJsonArray("required").asList().stream().map(value -> value.getAsString()).toList());
        assertTrue(batchSchema.getAsJsonObject("properties").has("commonAttachments"));
        assertTrue(!batchSchema.getAsJsonObject("properties").has("mode"));
        assertTrue(!Files.readString(MANIFEST).contains("email_send_retry"));
    }
}
