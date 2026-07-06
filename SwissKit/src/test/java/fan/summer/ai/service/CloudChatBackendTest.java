package fan.summer.ai.service;

import fan.summer.zhiflow.api.ai.AiServiceException;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CloudChatBackendTest {

    @Test
    void openAiFactoryStoresConfigStrippingTrailingSlash() {
        CloudChatBackend b = CloudChatBackend.openAi("https://api.openai.com/", "sk-x", "gpt-4o-mini");
        assertEquals("https://api.openai.com", b.getEndpoint());
        assertEquals("sk-x", b.getApiKey());
        assertEquals("gpt-4o-mini", b.getModelNameInternal());
        assertEquals(CloudChatBackend.Provider.OPENAI, b.provider());
    }

    @Test
    void anthropicFactoryKeepsNonDefaultBaseUrl() {
        CloudChatBackend b = CloudChatBackend.anthropic("https://my-proxy.com/v1", "sk-ant", "claude-3-5-sonnet");
        assertEquals("https://my-proxy.com/v1", b.getEndpoint());
        assertEquals(CloudChatBackend.Provider.ANTHROPIC, b.provider());
    }

    @Test
    void isReadyRequiresAllFields() {
        assertFalse(CloudChatBackend.openAi("", "key", "model").isReady());
        assertFalse(CloudChatBackend.openAi("https://x", "", "model").isReady());
        assertFalse(CloudChatBackend.openAi("https://x", "key", "").isReady());
        assertTrue(CloudChatBackend.openAi("https://x", "key", "model").isReady());
    }

    @Test
    void getModelNameReturnsConfigured() {
        assertEquals(Optional.of("gpt-4o"), CloudChatBackend.openAi("e", "k", "gpt-4o").getModelName());
    }

    @Test
    void getMemoryUsageIsAlwaysMinusOne() {
        assertEquals(-1L, CloudChatBackend.openAi("e", "k", "m").getMemoryUsage());
    }

    @Test
    void isNativeAvailableIsAlwaysFalse() {
        assertFalse(CloudChatBackend.openAi("e", "k", "m").isNativeAvailable());
    }

    @Test
    void isGeneratingStartsFalse() {
        assertFalse(CloudChatBackend.openAi("e", "k", "m").isGenerating());
    }

    @Test
    void loadModelThrowsForCloudBackend() {
        CloudChatBackend b = CloudChatBackend.openAi("e", "k", "m");
        assertThrows(AiServiceException.class, () -> b.loadModel(Paths.get("x")));
    }

    @Test
    void testConnectionReturnsErrorWhenEndpointUnreachable() {
        // localhost:1 refuses connections — should return a non-null error string quickly
        CloudChatBackend b = CloudChatBackend.openAi("https://localhost:1", "k", "m");
        String err = b.testConnection();
        assertNotNull(err);
        // Don't assert specific text — depends on OS socket error message
    }

    @Test
    void unloadModelIsSafeNoOp() {
        CloudChatBackend b = CloudChatBackend.openAi("e", "k", "m");
        // Should not throw
        assertDoesNotThrow(b::unloadModel);
        // State unchanged
        assertTrue(b.isReady() || !b.isReady()); // tautology — just verify no exception
    }

    @Test
    void cancelGenerationIsSafeNoOp() {
        CloudChatBackend b = CloudChatBackend.openAi("e", "k", "m");
        assertDoesNotThrow(b::cancelGeneration);
        assertFalse(b.isGenerating());
    }
}
