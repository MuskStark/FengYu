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

public class AnthropicService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(AnthropicService.class);
    private static final int MAX_TOOL_ROUNDS = 5;

    private final HttpClient httpClient;
    private final ToolRegistry toolRegistry = new ToolRegistry();
    private volatile boolean generating = false;
    private volatile InputStream activeStream;

    private String endpoint;
    private String apiKey;
    private String modelName;

    public AnthropicService() {
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
        throw new AiServiceException("Local model loading not supported for Anthropic mode");
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
            callback.onError(new AiServiceException("Anthropic service not configured"));
            return;
        }
        generating = true;
        Thread.ofVirtual().start(() -> {
            try {
                chatWithToolLoop(history, temperature, topP, maxTokens, callback, 0, new AtomicBoolean(false));
            } catch (Exception e) {
                log.error("Anthropic generation error", e);
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

        List<Object> messages = buildAnthropicMessages(history);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        body.put("top_p", topP);
        body.put("stream", true);
        body.put("system", systemPrompt);
        body.put("messages", messages);
        if (toolRegistry.hasTools()) {
            body.put("tools", buildAnthropicTools());
        }

        String jsonBody = JsonBuilder.toJson(body);
        String url = endpoint + "/v1/messages";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .timeout(Duration.ofSeconds(120))
            .build();

        HttpResponse<InputStream> resp = sendWithRetry(request);

        if (resp.statusCode() != 200) {
            String errBody;
            try (InputStream is = resp.body()) {
                errBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw new AiServiceException("Anthropic API error (HTTP " + resp.statusCode() + "): " + errBody);
        }

        activeStream = resp.body();
        StringBuilder fullResponse = new StringBuilder();
        List<AiToolCall> toolCalls = new ArrayList<>();
        StringBuilder currentToolArgs = new StringBuilder();
        String[] currentToolName = {null};
        String[] currentToolId = {null};
        long startTime = System.nanoTime();
        int[] tokenCount = {0};

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            String eventType = "";
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event: ")) {
                    eventType = line.substring(7).trim();
                    continue;
                }
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if (data.isEmpty()) continue;

                Map<String, Object> chunk;
                try {
                    chunk = JsonParser.parseObject(data);
                } catch (Exception e) {
                    log.warn("Malformed SSE chunk, skipping: {}", e.getMessage());
                    continue;
                }
                String type = chunk.containsKey("type") ? String.valueOf(chunk.get("type")) : eventType;

                switch (type) {
                    case "content_block_delta" -> {
                        Map<String, Object> delta = (Map<String, Object>) chunk.get("delta");
                        if (delta == null) break;
                        if (delta.containsKey("text")) {
                            String text = (String) delta.get("text");
                            fullResponse.append(text);
                            tokenCount[0]++;
                            Platform.runLater(() -> callback.onToken(text));
                        } else if (delta.containsKey("partial_json")) {
                            currentToolArgs.append(delta.get("partial_json"));
                        }
                    }
                    case "content_block_start" -> {
                        Map<String, Object> contentBlock = (Map<String, Object>) chunk.get("content_block");
                        if (contentBlock != null && "tool_use".equals(contentBlock.get("type"))) {
                            currentToolName[0] = (String) contentBlock.get("name");
                            currentToolId[0] = (String) contentBlock.get("id");
                            currentToolArgs.setLength(0);
                        }
                    }
                    case "content_block_stop" -> {
                        if (currentToolName[0] != null && currentToolId[0] != null) {
                            Map<String, Object> args;
                            try {
                                Object parsed = JsonParser.parse(currentToolArgs.toString());
                                args = parsed instanceof Map ? (Map<String, Object>) parsed : Map.of("raw", currentToolArgs.toString());
                            } catch (Exception e) {
                                args = Map.of("raw", currentToolArgs.toString());
                            }
                            toolCalls.add(new AiToolCall(currentToolId[0], currentToolName[0], args));
                            currentToolName[0] = null;
                            currentToolId[0] = null;
                        }
                    }
                }
            }
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

    @SuppressWarnings("unchecked")
    private List<Object> buildAnthropicMessages(List<AiChatMessage> history) {
        List<Object> messages = new ArrayList<>();
        for (AiChatMessage msg : history) {
            if (msg.role() == AiChatMessage.Role.SYSTEM) continue;
            if (msg.role() == AiChatMessage.Role.TOOL) {
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("type", "tool_result");
                content.put("tool_use_id", msg.toolCallId() != null ? msg.toolCallId() : "");
                content.put("content", msg.content() != null ? msg.content() : "");
                messages.add(Map.of("role", "user", "content", List.of(content)));
            } else if (msg.hasToolCalls()) {
                List<Object> contentBlocks = new ArrayList<>();
                if (msg.content() != null && !msg.content().isEmpty()) {
                    contentBlocks.add(Map.of("type", "text", "text", msg.content()));
                }
                for (AiToolCall tc : msg.toolCalls()) {
                    Map<String, Object> toolUse = new LinkedHashMap<>();
                    toolUse.put("type", "tool_use");
                    toolUse.put("id", tc.id());
                    toolUse.put("name", tc.name());
                    toolUse.put("input", tc.arguments());
                    contentBlocks.add(toolUse);
                }
                messages.add(Map.of("role", "assistant", "content", contentBlocks));
            } else {
                messages.add(Map.of("role", msg.role().name().toLowerCase(), "content", msg.content()));
            }
        }
        return messages;
    }

    private List<Object> buildAnthropicTools() {
        List<Object> tools = new ArrayList<>();
        for (AiTool tool : toolRegistry.getAll()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", tool.getName());
            t.put("description", tool.getDescription());
            t.put("input_schema", buildJsonSchema(tool.getParameters()));
            tools.add(t);
        }
        return tools;
    }

    private static Map<String, Object> buildJsonSchema(List<AiToolParam> params) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (AiToolParam p : params) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", p.type());
            prop.put("description", p.description());
            properties.put(p.name(), prop);
            if (p.required()) required.add(p.name());
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
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
            String url = endpoint + "/v1/messages";
            String body = JsonBuilder.toJson(Map.of(
                "model", modelName,
                "max_tokens", 5,
                "messages", List.of(Map.of("role", "user", "content", "Hi"))
            ));
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
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
