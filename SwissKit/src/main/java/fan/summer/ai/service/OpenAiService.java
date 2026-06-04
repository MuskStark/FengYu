package fan.summer.ai.service;

import fan.summer.ai.tools.ToolCallParser;
import fan.summer.ai.tools.ToolExecutor;
import fan.summer.ai.tools.ToolSchemaBuilder;
import fan.summer.ai.util.JsonHelper;
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

/**
 * {@link AiService} implementation for calling OpenAI-compatible chat completion APIs.
 * Supports streaming responses, tool calling, and multi-round conversations.
 * Does not support local model loading.
 *
 * <p>Configure the service using {@link #configure(String, String, String)}
 * before invoking {@link #chat(List, AiStreamCallback)}.
 *
 * @see AiService
 */
public class OpenAiService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);
    private static final int MAX_TOOL_ROUNDS = 5;

    private final HttpClient httpClient;
    private volatile boolean generating = false;
    private volatile InputStream activeStream;

    private String endpoint;
    private String apiKey;
    private String modelName;

    /**
     * Constructs an {@code OpenAiService} with a default HTTP/1.1 client
     * and a 30-second connection timeout.
     */
    public OpenAiService() {
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    /**
     * Configures the endpoint, API key, and model name for this service.
     *
     * @param endpoint  the base URL of the OpenAI-compatible API; trailing slashes are stripped
     * @param apiKey    the API key for authentication
     * @param modelName the model identifier (e.g., {@code "gpt-4o"})
     */
    public void configure(String endpoint, String apiKey, String modelName) {
        this.endpoint = endpoint == null ? "" : (endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
        this.apiKey = apiKey;
        this.modelName = modelName;
    }

    @Override
    public void loadModel(Path modelPath) throws AiServiceException {
        throw new AiServiceException("Local model loading not supported for OpenAI mode");
    }

    @Override public void unloadModel() {}

    /**
     * Returns {@code true} if the service has been configured with non-blank
     * endpoint, API key, and model name.
     *
     * @return true if ready, false otherwise
     */
    @Override public boolean isReady() {
        return endpoint != null && !endpoint.isBlank()
            && apiKey != null && !apiKey.isBlank()
            && modelName != null && !modelName.isBlank();
    }

    @Override public Optional<String> getModelName() { return Optional.ofNullable(modelName); }

    /** Returns the configured API endpoint (for planner direct calls). */
    public String getEndpoint() { return endpoint; }

    /** Returns the configured API key (for planner direct calls). */
    public String getApiKey() { return apiKey; }

    /** Returns the configured model name string (for planner direct calls). */
    public String getModelNameInternal() { return modelName; }

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

    /**
     * Sends a chat completion request and handles a multi-round tool-call loop.
     * The loop terminates when either no more tool calls are generated or
     * {@code MAX_TOOL_ROUNDS} rounds have been completed.
     */
    @SuppressWarnings("unchecked")
    private void chatWithToolLoop(List<AiChatMessage> history, float temperature, float topP,
                                  int maxTokens, AiStreamCallback callback, int round,
                                  AtomicBoolean hadToolCall) throws Exception {
        if (round >= MAX_TOOL_ROUNDS) return;

        String systemPrompt = SwissKitJSettingUi.getAiSystemPrompt();
        String toolDefs = ToolSchemaBuilder.buildPromptDefinitions(AiServiceProvider.getTools());
        if (!toolDefs.isEmpty()) systemPrompt += "\n\n" + toolDefs;

        List<Object> messages = buildMessages(history, systemPrompt);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("temperature", temperature);
        body.put("top_p", topP);
        body.put("max_tokens", maxTokens);
        body.put("stream", true);
        body.put("messages", messages);
        if (AiServiceProvider.hasTools()) {
            body.put("tools", ToolSchemaBuilder.buildOpenAiTools(AiServiceProvider.getTools()));
        }

        String jsonBody = JsonHelper.toJson(body);
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
        StringBuilder reasoningBuffer = new StringBuilder();
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
                    chunk = JsonHelper.parseObject(data);
                } catch (Exception e) {
                    log.warn("Malformed SSE chunk, skipping: {}", e.getMessage());
                    continue;
                }
                Map<String, Object> delta = JsonHelper.getMap(chunk, "choices.0.delta");
                if (delta == null) continue;

                String content = (String) delta.get("content");
                if (content != null) {
                    fullResponse.append(content);
                    tokenCount[0]++;
                    String text = content;
                    Platform.runLater(() -> callback.onToken(text));
                }

                String reasoningChunk = (String) delta.get("reasoning_content");
                if (reasoningChunk != null) {
                    reasoningBuffer.append(reasoningChunk);
                }

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

        List<AiToolCall> toolCalls = new ArrayList<>();
        for (int idx : toolCallArgs.keySet()) {
            String argsJson = toolCallArgs.get(idx).toString();
            Map<String, Object> args;
            try {
                args = JsonHelper.parseObject(argsJson);
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

        String reasoningText = reasoningBuffer.isEmpty() ? null : reasoningBuffer.toString();

        if (!toolCalls.isEmpty() && AiServiceProvider.hasTools()) {
            hadToolCall.set(true);
            history.add(AiChatMessage.assistantWithToolsAndReasoning(fullResponse.toString(), toolCalls, reasoningText));
            ToolExecutor.executeAndFeed(toolCalls, history, callback);
            chatWithToolLoop(history, temperature, topP, maxTokens, callback, round + 1, hadToolCall);
        } else {
            String clean = hadToolCall.get() ? ToolCallParser.stripToolCalls(fullResponse.toString()) : fullResponse.toString();
            history.add(AiChatMessage.assistantWithReasoning(clean, reasoningText));
            String finalClean = clean;
            int count = tokenCount[0];
            Platform.runLater(() -> callback.onComplete(finalClean, count, tokPerSec));
        }
    }

    /**
     * Builds the list of messages in OpenAI format from the conversation history.
     * A system message is prepended with the provided system prompt, and tool call
     * messages are converted to the {@code tool_calls} structure used by the API.
     *
     * @param history      the conversation history
     * @param systemPrompt the system prompt to prepend
     * @return a list of message maps suitable for the OpenAI chat completions API
     */
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
                if (msg.hasReasoningContent()) m.put("reasoning_content", msg.reasoningContent());
                List<Object> tcList = new ArrayList<>();
                for (AiToolCall tc : msg.toolCalls()) {
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", tc.name());
                    fn.put("arguments", JsonHelper.toJson(tc.arguments()));
                    Map<String, Object> toolCall = new LinkedHashMap<>();
                    toolCall.put("id", tc.id());
                    toolCall.put("type", "function");
                    toolCall.put("function", fn);
                    tcList.add(toolCall);
                }
                m.put("tool_calls", tcList);
                messages.add(m);
            } else if (msg.role() == AiChatMessage.Role.ASSISTANT && msg.hasReasoningContent()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", "assistant");
                m.put("content", msg.content() != null ? msg.content() : "");
                m.put("reasoning_content", msg.reasoningContent());
                messages.add(m);
            } else {
                messages.add(Map.of("role", msg.role().name().toLowerCase(), "content", msg.content()));
            }
        }
        return messages;
    }

    @Override public void cancelGeneration() {
        generating = false;
        InputStream stream = activeStream;
        if (stream != null) {
            try { stream.close(); } catch (Exception ignored) {}
        }
    }

    @Override public void registerTool(AiTool tool) { AiServiceProvider.registerTool(tool); }
    @Override public void unregisterTool(String toolName) { AiServiceProvider.unregisterTool(toolName); }
    @Override public List<AiTool> getTools() { return AiServiceProvider.getTools(); }

    /**
     * Sends the HTTP request, retrying once on socket timeout.
     *
     * @param request the {@link HttpRequest} to send
     * @return the HTTP response with an {@link InputStream} body
     * @throws Exception if both the initial and retry attempts fail
     */
    private HttpResponse<InputStream> sendWithRetry(HttpRequest request) throws Exception {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.net.SocketTimeoutException e) {
            log.warn("Request timed out, retrying once: {}", e.getMessage());
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        }
    }

    /**
     * Tests connectivity to the configured endpoint with a minimal request.
     *
     * @return {@code null} if the connection succeeds (HTTP 200), otherwise
     *         an error string describing the failure
     */
    public String testConnection() {
        try {
            String url = endpoint + "/v1/chat/completions";
            String body = JsonHelper.toJson(Map.of(
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
