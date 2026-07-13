package fan.summer.fengyu.plugin.runtime;

import fan.summer.fengyu.plugin.market.PluginPackageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PluginProcessManagerTest {
    @TempDir Path temp;

    @Test
    void invokesIsolatedJsonRpcWorker() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = Path.of("target", "test-classes").toAbsolutePath().toString();
        String command = "\"" + java + "\" -cp \"" + classpath + "\" " + EchoWorker.class.getName();
        String manifest = """
            {"schemaVersion":1,"id":"com.example.worker","name":"Worker","description":"test",
             "version":"1.0.0","ui":{"entry":"ui/index.html"},
             "backend":{"command":%s,"protocol":"json-rpc-2.0"},"permissions":[]}
            """.formatted(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(command));
        PluginPackageService packages = new PluginPackageService(temp.resolve("plugins").toString());
        packages.install(new MockMultipartFile("file", "worker.fyp", "application/zip", archive(manifest)));
        PluginProcessManager manager = new PluginProcessManager(packages, new PluginFileGrantService());
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) manager.invoke("com.example.worker", "echo", Map.of());
        assertEquals("ok", result.get("value"));
        manager.close();
    }

    private byte[] archive(String manifest) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            add(zip, "manifest.json", manifest); add(zip, "ui/index.html", "test");
        }
        return bytes.toByteArray();
    }
    private static void add(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name)); zip.write(value.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }

    public static final class EchoWorker {
        private static final Pattern ID = Pattern.compile("\\\"id\\\":\\\"([^\\\"]+)\\\"");
        public static void main(String[] args) throws Exception {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) {
                    var matcher = ID.matcher(line); String id = matcher.find() ? matcher.group(1) : "";
                    System.out.println("{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"value\":\"ok\"}}");
                    System.out.flush();
                }
            }
        }
    }
}
