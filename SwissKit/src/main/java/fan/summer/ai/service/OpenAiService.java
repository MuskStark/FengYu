package fan.summer.ai.service;

import fan.summer.ai.tools.ToolCallParser;
import fan.summer.ai.tools.ToolRegistry;
import fan.summer.api.ai.*;
import fan.summer.ui.setting.SwissKitJSettingUi;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class OpenAiService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);
    private static final int MAX_TOOL_ROUNDS = 5;

    private final HttpClient httpClient;
    private final ToolRegistry toolRegistry = new ToolRegistry();
    private volatile boolean generating = false;
    private volatile InputStream activeStream;

    private String endpoint;
    private String apiKey;
    private String modelName;

    public OpenAiService() {
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    public void configure(String endpoint, String apiKey, String modelName) {
        this.endpoint = endpoint == null ? "" : (endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
        this.apiKey = apiKey;
        this.modelName = modelName;
    }

    @Override public void loadModel(Path modelPath) throws AiServiceException {
        throw new AiServiceException("Local model loading not supported for OpenAI mode");
    }

    @Override public void unloadModel() {}

    @Override public boolean isReady() {
        return endpoint != null && !endpoint.isBlank()
            && apiKey != null && !apiKey.isBlank()
            && modelName != null && !modelName.isBlank();
    }

    @Override public Optional<String> getModelName() {
        return Optional.ofNullable(modelName);
    }

    @Override public long getMemoryUsage() { return -1; }

    @Override public boolean isGenerating() { return generating; }

    @Override
    public void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException {
        chat(history, SwissKitJSettingUi.getAiTemperature(), SwissKitJSettingUi.getAiTopP(),
             SwissKitJSettingUi.getAiMaxTokens(), callback);
    }

    @Override
    public void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
                     AiStreamCallback callback) throws AiServiceException {
        if (!isReady()) {
            callback.onError(new AiServiceException("OpenAI service not configured"));
            return;
        }
        generating = true;
        Thread.ofVirtual().start(() -> {
            try {
                chatWithToolLoop(history, temperature, topP, maxTokens, callback, 0, new AtomicBoolean(false));
            } catch (Exception e) {
                log.error("OpenAI generation error", e);
                Platform.runLater(() -> callback.onError(e));
            } finally {
                generating = false;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void chatWithToolLoop(List<AiChatMessage> history, float temperature, float topP,
                                  int maxTokens, AiStreamCallback callback, int round,
                                  AtomicBoolean hadToolCall) throws Exception {
        if (round >= MAX_TOOL_ROUNDS) return;

        String systemPrompt = SwissKitJSettingUi.getAiSystemPrompt();
        String toolDefs = toolRegistry.buildToolDefinitions();
        if (!toolDefs.isEmpty()) systemPrompt += "\n\n" + toolDefs;

        List<Object> messages = buildMessages(history, systemPrompt);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("temperature", temperature);
        body.put("top_p", topP);
        body.put("max_tokens", maxTokens);
        body.put("stream", true);
        body.put("messages", messages);
        if (toolRegistry.hasTools()) {
            body.put("tools", buildOpenAiTools());
        }

        String jsonBody = JsonBuilder.toJson(body);
        String url = endpoint + "/v1/chat/completions";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .timeout(Duration.ofSeconds(120))
            .build();

        HttpResponse<InputStream> resp = sendWithRetry(request);

        if (resp.statusCode() != 200) {
            String errBody;
            try (InputStream is = resp.body()) {
                errBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw new AiServiceException("OpenAI API error (HTTP " + resp.statusCode() + "): " + errBody);
        }

        activeStream = resp.body();
        StringBuilder fullResponse = new StringBuilder();
        Map<Integer, StringBuilder> toolCallArgs = new LinkedHashMap<>();
        Map<Integer, String> toolCallNames = new LinkedHashMap<>();
        Map<Integer, String> toolCallIds = new LinkedHashMap<>();
        long startTime = System.nanoTime();
        int[] tokenCount = {0};

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) break;
                if (data.isEmpty()) continue;

                Map<String, Object> chunk;
                try {
                    chunk = JsonParser.parseObject(data);
                } catch (Exception e) {
                    log.warn("Malformed SSE chunk, skipping: {}", e.getMessage());
                    continue;
                }
                Map<String, Object> delta = JsonParser.getMap(chunk, "choices.0.delta");
                if (delta == null) continue;

                // Text content
                String content = (String) delta.get("content");
                if (content != null) {
                    fullResponse.append(content);
                    tokenCount[0]++;
                    String text = content;
                    Platform.runLater(() -> callback.onToken(text));
                }

                // Tool calls
                List<Object> toolCallsDelta = (List<Object>) delta.get("tool_calls");
                if (toolCallsDelta != null) {
                    for (Object tcObj : toolCallsDelta) {
                        if (!(tcObj instanceof Map)) continue;
                        Map<String, Object> tc = (Map<String, Object>) tcObj;
                        int idx = ((Number) tc.get("index")).intValue();
                        if (tc.containsKey("id")) toolCallIds.put(idx, (String) tc.get("id"));
                        Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                        if (fn != null) {
                            if (fn.containsKey("name")) toolCallNames.put(idx, (String) fn.get("name"));
                            if (fn.containsKey("arguments")) {
                                toolCallArgs.computeIfAbsent(idx, k -> new StringBuilder())
                                    .append(fn.get("arguments"));
                            }
                        }
                    }
                }
            }
        }

        // Assemble tool calls
        List<AiToolCall> toolCalls = new ArrayList<>();
        for (int idx : toolCallArgs.keySet()) {
            String argsJson = toolCallArgs.get(idx).toString();
            Map<String, Object> args;
            try {
                args = (Map<String, Object>) JsonParser.parse(argsJson);
                if (args == null) args = Map.of();
            } catch (Exception e) {
                args = Map.of("raw", argsJson);
            }
            toolCalls.add(new AiToolCall(
                toolCallIds.getOrDefault(idx, "tc_" + idx),
                toolCallNames.getOrDefault(idx, "unknown"),
                args
            ));
        }

        long elapsed = System.nanoTime() - startTime;
        double tokPerSec = tokenCount[0] > 0 ? tokenCount[0] * 1_000_000_000.0 / elapsed : 0;

        if (!toolCalls.isEmpty() && toolRegistry.hasTools()) {
            hadToolCall.set(true);
            history.add(AiChatMessage.assistantWithTools(fullResponse.toString(), toolCalls));
            for (AiToolCall tc : toolCalls) {
                Platform.runLater(() -> callback.onToolCall(tc));
                AiToolResult result = toolRegistry.execute(tc.name(), tc.arguments());
                Platform.runLater(() -> callback.onToolResult(tc.id(), result));
                history.add(AiChatMessage.toolResult(tc.id(), tc.name(), result.output()));
            }
            chatWithToolLoop(history, temperature, topP, maxTokens, callback, round + 1, hadToolCall);
        } else {
            String clean = hadToolCall.get() ? ToolCallParser.stripToolCalls(fullResponse.toString()) : fullResponse.toString();
            String finalClean = clean;
            int count = tokenCount[0];
            Platform.runLater(() -> callback.onComplete(finalClean, count, tokPerSec));
        }
    }

    private List<Object> buildMessages(List<AiChatMessage> history, String systemPrompt) {
        List<Object> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        for (AiChatMessage msg : history) {
            if (msg.role() == AiChatMessage.Role.TOOL) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", "tool");
                m.put("tool_call_id", msg.toolCallId() != null ? msg.toolCallId() : "");
                m.put("content", msg.content() != null ? msg.content() : "");
                messages.add(m);
            } else if (msg.hasToolCalls()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", "assistant");
                m.put("content", msg.content() != null ? msg.content() : "");
                List<Object> tcList = new ArrayList<>();
                for (AiToolCall tc : msg.toolCalls()) {
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", tc.name());
                    fn.put("arguments", JsonBuilder.toJson(tc.arguments()));
                    Map<String, Object> toolCall = new LinkedHashMap<>();
                    toolCall.put("id", tc.id());
                    toolCall.put("type", "function");
                    toolCall.put("function", fn);
                    tcList.add(toolCall);
                }
                m.put("tool_calls", tcList);
                messages.add(m);
            } else {
                messages.add(Map.of("role", msg.role().name().toLowerCase(), "content", msg.content()));
            }
        }
        return messages;
    }

    private List<Object> buildOpenAiTools() {
        List<Object> tools = new ArrayList<>();
        for (AiTool tool : toolRegistry.getAll()) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", tool.getName());
            fn.put("description", tool.getDescription());
            fn.put("parameters", tool.getParameters());
            tools.add(Map.of("type", "function", "function", fn));
        }
        return tools;
    }

    @Override public void cancelGeneration() {
        generating = false;
        InputStream stream = activeStream;
        if (stream != null) {
            try { stream.close(); } catch (Exception ignored) {}
        }
    }

    @Override public void registerTool(AiTool tool) { toolRegistry.register(tool); }
    @Override public void unregisterTool(String toolName) { toolRegistry.unregister(toolName); }
    @Override public List<AiTool> getTools() { return toolRegistry.getAll(); }

    private HttpResponse<InputStream> sendWithRetry(HttpRequest request) throws Exception {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.net.SocketTimeoutException e) {
            log.warn("Request timed out, retrying once: {}", e.getMessage());
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        }
    }

    public String testConnection() {
        try {
            String url = endpoint + "/v1/chat/completions";
            String body = JsonBuilder.toJson(Map.of(
                "model", modelName,
                "messages", List.of(Map.of("role", "user", "content", "Hi")),
                "max_tokens", 5
            ));
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(15))
                .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return null;
            return "HTTP " + resp.statusCode() + ": " + resp.body();
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}
