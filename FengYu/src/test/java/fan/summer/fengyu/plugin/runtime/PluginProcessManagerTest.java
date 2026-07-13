package fan.summer.fengyu.plugin.runtime;

import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.setup.DataSourceConfig;
import fan.summer.fengyu.setup.DataSourceConfigService;
import fan.summer.fengyu.setup.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginProcessManagerTest {
    @TempDir Path temp;

    @Test
    void invokesIsolatedJsonRpcWorker() throws Exception {
        PluginProcessManager manager = manager();
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) manager.invoke("com.example.worker", "echo", Map.of());
        assertEquals("ok", result.get("value"));
        manager.close();
    }

    @Test
    void preservesRpcErrorMessage() throws Exception {
        PluginProcessManager manager = manager();
        var error = assertThrows(IllegalArgumentException.class,
            () -> manager.invoke("com.example.worker", "error", Map.of()));
        assertTrue(error.getMessage().contains("bad workbook"));
        manager.close();
    }

    @Test
    void reportsWorkerEof() throws Exception {
        PluginProcessManager manager = manager();
        var error = assertThrows(IllegalStateException.class,
            () -> manager.invoke("com.example.worker", "eof", Map.of()));
        assertTrue(error.getMessage().contains("stopped unexpectedly"));
        manager.close();
    }

    @Test
    void injectsDatabaseEnvironmentIntoPermittedWorker() throws Exception {
        PluginProcessManager manager = manager(List.of("database"));
        @SuppressWarnings("unchecked") Map<String, Object> result =
            (Map<String, Object>) manager.invoke("com.example.worker", "environment", Map.of());
        assertEquals("jdbc:h2:mem:worker-host", result.get("value"));
        manager.close();
    }

    private PluginProcessManager manager() throws Exception {
        return manager(List.of());
    }

    private PluginProcessManager manager(List<String> permissions) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = Path.of("target", "test-classes").toAbsolutePath().toString();
        String command = "\"" + java + "\" -cp \"" + classpath + "\" " + EchoWorker.class.getName();
        String manifest = """
            {"schemaVersion":1,"id":"com.example.worker","name":"Worker","description":"test",
             "version":"1.0.0","ui":{"entry":"ui/index.html"},
             "backend":{"command":%s,"protocol":"json-rpc-2.0"},"permissions":%s}
            """.formatted(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(command),
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(permissions));
        PluginPackageService packages = new PluginPackageService(temp.resolve("plugins").toString());
        packages.install(new MockMultipartFile("file", "worker.fyp", "application/zip", archive(manifest)));
        DataSourceConfigService dataSources = new DataSourceConfigService(temp.resolve("host").toString());
        dataSources.save(new DataSourceConfig(DbType.H2, "jdbc:h2:mem:worker-host", "org.h2.Driver",
            "org.hibernate.dialect.H2Dialect", "sa", "do-not-log-me", null));
        PluginRuntimeEnvironmentService runtimeEnvironment = new PluginRuntimeEnvironmentService(
            dataSources, temp.resolve("plugin-data").toString());
        return new PluginProcessManager(packages, new PluginFileGrantService(), runtimeEnvironment);
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
                    if (line.contains("\"method\":\"eof\"")) return;
                    System.out.println("third-party diagnostic line");
                    System.out.println("{\"jsonrpc\":\"2.0\",\"id\":\"other\",\"result\":{}}");
                    if (line.contains("\"method\":\"error\"")) {
                        System.out.println("{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"error\":{\"code\":-32000,\"message\":\"bad workbook\"}}");
                    } else if (line.contains("\"method\":\"environment\"")) {
                        String url = System.getenv("FENGYU_DB_URL");
                        System.out.println("{\"jsonrpc\":\"2.0\",\"id\":\"" + id
                            + "\",\"result\":{\"value\":\"" + url + "\"}}");
                    } else {
                        System.out.println("{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"value\":\"ok\"}}");
                    }
                    System.out.flush();
                }
            }
        }
    }
}
