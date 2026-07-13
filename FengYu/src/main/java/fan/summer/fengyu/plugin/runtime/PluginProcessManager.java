package fan.summer.fengyu.plugin.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Owns isolated plugin backend processes and their newline-delimited JSON-RPC channel. */
@Service
public class PluginProcessManager {
    private final PluginPackageService packages;
    private final PluginFileGrantService files;
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final Map<String, Worker> workers = new ConcurrentHashMap<>();

    public PluginProcessManager(PluginPackageService packages, PluginFileGrantService files) {
        this.packages = packages;
        this.files = files;
    }

    public Object invoke(String pluginId, String method, Map<String, Object> params) {
        if (!packages.isEnabled(pluginId)) throw new IllegalArgumentException("Plugin is disabled: " + pluginId);
        PluginManifest manifest = packages.find(pluginId)
            .orElseThrow(() -> new IllegalArgumentException("Plugin is not installed: " + pluginId));
        if (manifest.backend() == null || manifest.backend().command() == null || manifest.backend().command().isBlank()) {
            throw new IllegalArgumentException("Plugin has no backend: " + pluginId);
        }
        if (!"json-rpc-2.0".equals(manifest.backend().protocol())) {
            throw new IllegalArgumentException("Unsupported plugin backend protocol");
        }
        Worker worker = workers.compute(pluginId, (id, current) -> current != null && current.alive()
            ? current : start(id, manifest));
        try {
            @SuppressWarnings("unchecked") Map<String, Object> resolved = (Map<String, Object>) resolveRefs(pluginId, params == null ? Map.of() : params);
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                return executor.submit(() -> worker.invoke(method, resolved)).get(60, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new IllegalStateException("Plugin call timed out after 60 seconds");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Plugin call was interrupted", e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Plugin call failed", e.getCause());
            } finally {
                executor.shutdownNow();
            }
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException) {
                workers.remove(pluginId, worker);
                worker.close();
            }
            throw e;
        }
    }

    private Object resolveRefs(String pluginId, Object value) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("id") instanceof String id && id.startsWith("ref_") && map.get("kind") != null) {
                return files.resolve(pluginId, id).toString();
            }
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            map.forEach((key, item) -> out.put(String.valueOf(key), resolveRefs(pluginId, item)));
            return out;
        }
        if (value instanceof List<?> list) return list.stream().map(item -> resolveRefs(pluginId, item)).toList();
        return value;
    }

    public void stop(String pluginId) {
        Worker worker = workers.remove(pluginId);
        if (worker != null) worker.close();
    }

    private Worker start(String id, PluginManifest manifest) {
        Path root = packages.directory(id);
        List<String> command = parseCommand(manifest.backend().command(), root);
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile());
            builder.environment().put("FENGYU_PLUGIN_ID", id);
            builder.environment().put("FENGYU_PLUGIN_ROOT", root.toString());
            Process process = builder.start();
            Thread.ofVirtual().name("plugin-" + id + "-stderr").start(() -> {
                try (BufferedReader errors = process.errorReader(StandardCharsets.UTF_8)) {
                    while (errors.readLine() != null) { /* drained; plugin logs remain process-isolated */ }
                } catch (IOException ignored) {}
            });
            return new Worker(process, json);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start plugin backend: " + e.getMessage(), e);
        }
    }

    private static List<String> parseCommand(String raw, Path root) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (Character.isWhitespace(c) && !quoted) {
                if (!current.isEmpty()) { parts.add(current.toString()); current.setLength(0); }
            } else current.append(c);
        }
        if (quoted) throw new IllegalArgumentException("Unclosed quote in backend command");
        if (!current.isEmpty()) parts.add(current.toString());
        if (parts.isEmpty()) throw new IllegalArgumentException("Backend command is empty");
        if ("java".equals(parts.getFirst()) || "${java}".equals(parts.getFirst())) {
            parts.set(0, Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString());
        }
        for (int i = 0; i < parts.size(); i++) {
            String value = parts.get(i).replace("${pluginRoot}", root.toString());
            if (i > 0 && !Path.of(value).isAbsolute() && Files.exists(root.resolve(value))) {
                value = root.resolve(value).normalize().toString();
            }
            parts.set(i, value);
        }
        return parts;
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }

    @PreDestroy public void close() { workers.values().forEach(Worker::close); workers.clear(); }

    private static final class Worker {
        private final Process process;
        private final ObjectMapper json;
        private final BufferedWriter writer;
        private final BufferedReader reader;

        Worker(Process process, ObjectMapper json) {
            this.process = process;
            this.json = json;
            this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        }

        boolean alive() { return process.isAlive(); }

        synchronized Object invoke(String method, Map<String, Object> params) {
            String id = UUID.randomUUID().toString();
            try {
                writer.write(json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params)));
                writer.newLine(); writer.flush();
                String line = reader.readLine();
                if (line == null) throw new IllegalStateException("Plugin backend stopped unexpectedly");
                JsonNode response = json.readTree(line);
                if (!id.equals(response.path("id").asText())) throw new IllegalStateException("Plugin returned a mismatched response id");
                if (response.hasNonNull("error")) throw new IllegalArgumentException(response.path("error").path("message").asText("Plugin call failed"));
                return json.treeToValue(response.get("result"), Object.class);
            } catch (IOException e) {
                throw new IllegalStateException("Plugin RPC failed: " + e.getMessage(), e);
            }
        }

        void close() {
            process.destroy();
            try { if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
        }
    }
}
