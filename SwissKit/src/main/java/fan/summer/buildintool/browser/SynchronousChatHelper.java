package fan.summer.buildintool.browser;

import fan.summer.api.ai.*;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wraps the streaming {@link AiService#chat(List, AiStreamCallback)} into a
 * synchronous blocking call. Used by {@link BrowserAutomateTool} to call the
 * planner LLM inside the think-act loop.
 *
 * <p>Timeout: 120 seconds per call (sufficient for complex planner reasoning).</p>
 */
public class SynchronousChatHelper {

    private static final PluginLogger log = LoggerFactory.getLogger(SynchronousChatHelper.class);
    private static final long TIMEOUT_SECONDS = 120;

    private SynchronousChatHelper() {}

    /**
     * Calls the AI service synchronously, blocking until the full response is available.
     *
     * @param history the conversation history (system + user + assistant messages)
     * @return the complete response text, or null if the service is unavailable or times out
     */
    public static String call(List<AiChatMessage> history) {
        AiService service = AiServiceProvider.getService().orElse(null);
        if (service == null || !service.isReady()) {
            log.warn("AI service not available for browser planner");
            return null;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> resultHolder = new AtomicReference<>();
        AtomicReference<Throwable> errorHolder = new AtomicReference<>();

        AiStreamCallback callback = new AiStreamCallback() {
            final StringBuilder buffer = new StringBuilder();

            @Override
            public void onToken(String fragment) {
                buffer.append(fragment);
            }

            @Override
            public void onComplete(String fullResponse, int tokensGenerated, double tokensPerSecond) {
                resultHolder.set(fullResponse != null ? fullResponse : buffer.toString());
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                errorHolder.set(error);
                latch.countDown();
            }
        };

        try {
            service.chat(history, callback);
        } catch (AiServiceException e) {
            log.error("AI service exception during browser planner call: {}", e.getMessage());
            return null;
        }

        try {
            boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                log.warn("Browser planner call timed out after {}s", TIMEOUT_SECONDS);
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        Throwable error = errorHolder.get();
        if (error != null) {
            log.error("Browser planner call errored: {}", error.getMessage());
            return null;
        }

        return resultHolder.get();
    }
}
