package fan.summer.buildintool.browser;

import fan.summer.ai.service.OpenAiService;
import fan.summer.ai.util.JsonHelper;
import fan.summer.api.ai.*;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Makes a <b>direct</b> HTTP call to the OpenAI-compatible API for the browser
 * planner, completely bypassing {@link AiService#chat(List, AiStreamCallback)}.
 *
 * <p>This is critical: if we used {@code service.chat()}, the planner call would
 * go through {@code chatWithToolLoop()} which injects tool definitions into the
 * system prompt. The planner LLM would then see {@code browser_automate} as a
 * callable tool and recursively invoke it, causing an infinite loop of browser
 * sessions.</p>
 *
 * <p>By calling the API directly without tools, the planner only sees the browser
 * automation system prompt and returns clean JSON actions.</p>
 */
public class SynchronousChatHelper {

    private static final PluginLogger log = LoggerFactory.getLogger(SynchronousChatHelper.class);
    private static final long TIMEOUT_SECONDS = 120;

    private SynchronousChatHelper() {}

    /**
     * Calls the AI API directly (no tools, no streaming) and returns the full
     * response text.
     *
     * @param history the conversation history (system + user + assistant messages)
     * @return the complete response text, or null if unavailable
     */
    public static String call(List<AiChatMessage> history) {
        AiService service = AiServiceProvider.getService().orElse(null);
        if (service == null || !service.isReady()) {
            log.warn("AI service not available for browser planner");
            return null;
        }

        // Only support OpenAI-compatible services for direct API call
        if (!(service instanceof OpenAiService openAiService)) {
            log.warn("Browser planner only supports OpenAI-compatible backends, got: {}",
                     service.getClass().getSimpleName());
            return null;
        }

        String endpoint = openAiService.getEndpoint();
        String apiKey = openAiService.getApiKey();
        String model = openAiService.getModelNameInternal();

        if (endpoint == null || endpoint.isBlank() || apiKey == null || apiKey.isBlank()
            || model == null || model.isBlank()) {
            log.warn("OpenAI service config incomplete for planner call");
            return null;
        }

        try {
            return callDirect(endpoint, apiKey, model, history);
        } catch (Exception e) {
            log.error("Direct planner API call failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Makes a direct, non-streaming POST to the OpenAI-compatible chat completions API.
     * No tool definitions are included — the model returns plain text/JSON.
     */
    private static String callDirect(String endpoint, String apiKey, String model,
                                      List<AiChatMessage> history) throws Exception {
        // Build messages array (no tool definitions)
        List<Object> messages = new ArrayList<>();
        for (AiChatMessage msg : history) {
            // Only include SYSTEM, USER, ASSISTANT roles — no TOOL messages
            if (msg.role() == AiChatMessage.Role.SYSTEM
                || msg.role() == AiChatMessage.Role.USER
                || msg.role() == AiChatMessage.Role.ASSISTANT) {
                String content = msg.content() != null ? msg.content() : "";
                messages.add(Map.of("role", msg.role().name().toLowerCase(), "content", content));
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0.3);    // Low temperature for consistent planner output
        body.put("max_tokens", 512);      // Planner only needs short JSON responses
        body.put("stream", false);        // No streaming — we need the full response
        body.put("messages", messages);
        // NO "tools" field — this is the key difference!

        String jsonBody = JsonHelper.toJson(body);
        String url = endpoint + "/v1/chat/completions";

        HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build();

        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            log.error("Planner API error (HTTP {}): {}", resp.statusCode(),
                      resp.body().length() > 500 ? resp.body().substring(0, 500) : resp.body());
            return null;
        }

        // Parse response: {"choices": [{"message": {"content": "..."}}]}
        Map<String, Object> responseMap = JsonHelper.parseObject(resp.body());
        Map<String, Object> message = JsonHelper.getMap(responseMap, "choices.0.message");
        if (message == null) {
            log.error("Unexpected planner response structure");
            return null;
        }

        String content = (String) message.get("content");
        if (content == null || content.isBlank()) {
            log.warn("Planner returned empty content");
            return null;
        }

        log.debug("Planner response: {}", content.length() > 200 ? content.substring(0, 200) + "..." : content);
        return content.trim();
    }
}
