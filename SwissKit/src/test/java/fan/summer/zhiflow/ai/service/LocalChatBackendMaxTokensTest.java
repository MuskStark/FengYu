package fan.summer.zhiflow.ai.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the pure max-tokens floor logic on {@link LocalChatBackend}.
 * (JavaFX / native IPC are not exercised — only the static helper.)
 */
class LocalChatBackendMaxTokensTest {

    @Test
    void qwen3_belowMinimum_isRaisedToFloor() {
        // The old default 512 truncated Qwen3 mid-<think>, producing a silently empty answer.
        assertEquals(LocalChatBackend.QWEN3_MIN_MAX_TOKENS,
                     LocalChatBackend.effectiveMaxTokens(true, 512));
        assertEquals(LocalChatBackend.QWEN3_MIN_MAX_TOKENS,
                     LocalChatBackend.effectiveMaxTokens(true, 64));
    }

    @Test
    void qwen3_atOrAboveMinimum_passesThrough() {
        assertEquals(LocalChatBackend.QWEN3_MIN_MAX_TOKENS,
                     LocalChatBackend.effectiveMaxTokens(true, LocalChatBackend.QWEN3_MIN_MAX_TOKENS));
        assertEquals(4096, LocalChatBackend.effectiveMaxTokens(true, 4096));
    }

    @Test
    void nonQwen3_isNeverRaised() {
        // A non-thinking model that genuinely wants a small budget must keep it.
        assertEquals(512, LocalChatBackend.effectiveMaxTokens(false, 512));
        assertEquals(64, LocalChatBackend.effectiveMaxTokens(false, 64));
    }
}
