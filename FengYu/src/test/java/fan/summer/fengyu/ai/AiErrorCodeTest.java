package fan.summer.fengyu.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch-D1 error contract: classification of the contract codes from the
 * official-SDK exception shapes (string seam), cause-chain depth, explicit codes,
 * and a classpath pin that the REAL SDK exception classes actually end with the
 * suffixes the matcher recognizes.
 */
class AiErrorCodeTest {

    @Test
    void classifiesSdkExceptionShapesByName() {
        assertEquals(AiErrorCode.RATE_LIMITED,
                AiErrorCode.classifyByTypeName("com.openai.errors.RateLimitException", "429"));
        assertEquals(AiErrorCode.RATE_LIMITED,
                AiErrorCode.classifyByTypeName("com.anthropic.errors.RateLimitException", "429"));
        assertEquals(AiErrorCode.CONTEXT_OVERFLOW,
                AiErrorCode.classifyByTypeName("com.openai.errors.BadRequestException",
                        "maximum context length is 128000 tokens"));
        assertEquals(AiErrorCode.PROVIDER_4XX,
                AiErrorCode.classifyByTypeName("com.anthropic.errors.BadRequestException", "invalid model"));
        assertEquals(AiErrorCode.AUTH_MISSING,
                AiErrorCode.classifyByTypeName("com.openai.errors.UnauthorizedException", "401"));
        assertEquals(AiErrorCode.NETWORK,
                AiErrorCode.classifyByTypeName("org.springframework.web.client.ResourceAccessException", "refused"));
        assertEquals(AiErrorCode.NETWORK,
                AiErrorCode.classifyByTypeName("org.springframework.ai.retry.TransientAiException", "503"));
        assertEquals(AiErrorCode.NETWORK,
                AiErrorCode.classifyByTypeName("com.openai.errors.OpenAIRetryableException", "503"));
        assertEquals(AiErrorCode.NETWORK,
                AiErrorCode.classifyByTypeName("com.anthropic.errors.AnthropicRetryableException", "503"));
        assertEquals(AiErrorCode.ABORTED,
                AiErrorCode.classifyByTypeName("java.lang.IllegalStateException", "cancelled"));
        assertEquals(null, AiErrorCode.classifyByTypeName("java.lang.RuntimeException", "mystery"));
    }

    @Test
    void theRealSdkClassesOnTheClasspathMatchTheRecognizedSuffixes() throws Exception {
        // Pin the matcher to reality: the SDK classes we key off exist and their
        // simple names end with exactly the recognized suffixes.
        assertTrue(Class.forName("com.openai.errors.RateLimitException")
                .getSimpleName().endsWith("RateLimitException"));
        assertTrue(Class.forName("com.anthropic.errors.RateLimitException")
                .getSimpleName().endsWith("RateLimitException"));
        assertTrue(Class.forName("com.openai.errors.BadRequestException")
                .getSimpleName().endsWith("BadRequestException"));
        assertTrue(Class.forName("com.anthropic.errors.BadRequestException")
                .getSimpleName().endsWith("BadRequestException"));
    }

    @Test
    void classificationIsCauseChainDeep() {
        Throwable wrapped = new RuntimeException("wrapper", new IllegalStateException("cancelled"));
        assertEquals(AiErrorCode.ABORTED, AiErrorCode.of(wrapped));
        Throwable rateLimited = new RuntimeException("wrapper",
                new org.springframework.web.client.ResourceAccessException("refused"));
        assertEquals(AiErrorCode.NETWORK, AiErrorCode.of(rateLimited));
    }

    @Test
    void explicitCodeWinsAndUnknownFallsThrough() {
        AiServiceException explicit = new AiServiceException("not configured", null, AiErrorCode.AUTH_MISSING);
        assertEquals(AiErrorCode.AUTH_MISSING, explicit.getCode());
        assertEquals(AiErrorCode.AUTH_MISSING, AiErrorCode.of(explicit));
        assertEquals(AiErrorCode.UNKNOWN, new AiServiceException("mystery").getCode());
    }
}
