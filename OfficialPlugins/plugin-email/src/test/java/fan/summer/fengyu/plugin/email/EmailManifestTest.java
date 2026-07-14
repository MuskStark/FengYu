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
    private static final Path MANIFEST = Path.of("../packages/email/manifest.json");

    @Test void officialManifestMatchesWorkerAndPackageContract() throws Exception {
        assertTrue(Files.isRegularFile(MANIFEST));
        JsonObject manifest = JSON.fromJson(Files.readString(MANIFEST), JsonObject.class);
        assertEquals("fan.summer.email", manifest.get("id").getAsString());
        assertTrue(manifest.get("version").getAsString().matches("\\d+\\.\\d+\\.\\d+"));
        assertTrue(manifest.get("official").getAsBoolean());
        assertEquals("ui/index.html", manifest.getAsJsonObject("ui").get("entry").getAsString());
        assertEquals("java -jar backend/worker.jar", manifest.getAsJsonObject("backend").get("command").getAsString());
        assertTrue(Files.isRegularFile(Path.of("ui-src/index.html")));
        assertTrue(Files.isRegularFile(Path.of("src/main/java/fan/summer/fengyu/plugin/email/EmailWorkerMain.java")));
        assertEquals(List.of("database", "network.email", "files.read", "files.write"),
            manifest.getAsJsonArray("permissions").asList().stream().map(value -> value.getAsString()).toList());

        List<String> expected = List.of("email_accounts_list", "email_contacts_query", "email_send_single",
            "email_send_batch", "email_send_status", "email_archive_fetch", "email_archive_query");
        var tools = manifest.getAsJsonArray("aiTools").asList();
        assertEquals(7, tools.size());
        var names = tools.stream().map(value -> value.getAsJsonObject().get("name").getAsString()).toList();
        var methods = tools.stream().map(value -> value.getAsJsonObject().get("method").getAsString()).toList();
        assertEquals(expected, names);
        assertEquals(expected, methods);
        assertEquals(names.size(), new HashSet<>(names).size());
        for (var value : tools) {
            JsonObject schema = JSON.fromJson(value.getAsJsonObject().get("inputSchema").getAsString(), JsonObject.class);
            assertEquals("object", schema.get("type").getAsString());
        }
        assertTrue(tools.get(2).getAsJsonObject().get("description").getAsString().contains("does not send"));
        assertTrue(tools.get(3).getAsJsonObject().get("description").getAsString().contains("does not send"));
    }
}
