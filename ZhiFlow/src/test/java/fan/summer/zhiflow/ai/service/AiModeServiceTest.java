package fan.summer.zhiflow.ai.service;

import fan.summer.zhiflow.api.ai.ChatBackend;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AiModeServiceTest {

    private static ChatBackend stub() {
        return new ChatBackend() {
            public void loadModel(java.nio.file.Path p) {}
            public void unloadModel() {}
            public boolean isReady() { return false; }
            public Optional<String> getModelName() { return Optional.empty(); }
            public long getMemoryUsage() { return -1; }
            public boolean isGenerating() { return false; }
            public boolean isNativeAvailable() { return false; }
            public void chat(java.util.List<fan.summer.zhiflow.api.ai.AiChatMessage> h,
                             fan.summer.zhiflow.api.ai.AiStreamCallback c) {}
            public void chat(java.util.List<fan.summer.zhiflow.api.ai.AiChatMessage> h,
                             float t, float tp, int m,
                             fan.summer.zhiflow.api.ai.AiStreamCallback c) {}
            public void cancelGeneration() {}
        };
    }

    @Test
    void switchModeStoresBackendAndFiresListeners() {
        AiModeService svc = new AiModeService();
        AtomicInteger fired = new AtomicInteger();
        svc.addOnStateChangeListener(fired::incrementAndGet);
        assertEquals("local", svc.getCurrentMode());
        ChatBackend b = stub();
        svc.switchMode("openai", b);
        assertEquals("openai", svc.getCurrentMode());
        assertTrue(svc.getService().isPresent());
        assertSame(b, svc.getService().get());
        assertEquals(1, fired.get());
    }
}
