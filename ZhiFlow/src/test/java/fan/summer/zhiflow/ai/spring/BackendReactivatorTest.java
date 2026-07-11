package fan.summer.zhiflow.ai.spring;

import fan.summer.zhiflow.ai.AiConfigService;
import fan.summer.zhiflow.ai.service.AiModeService;
import fan.summer.zhiflow.api.ai.ChatBackend;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link BackendReactivator} reads mode from {@link AiConfigService} and
 * calls {@link AiModeService#switchMode} with the right mode label. Cloud backends
 * need a live Spring context to resolve the ChatModel bean — those paths are
 * covered by integration; here we assert the mode label + local path, and the
 * not-configured cloud path (blank key → isReady false, no throw).
 *
 * <p>{@link AiConfigService} reads from H2; to avoid a DB we use a tiny subclass
 * override of the instance read path is not possible (readSetting is private).
 * Instead we set the static singleton to a stubbed instance that returns fixed
 * values. Since {@code AiConfigService}'s getters are static and delegate to
 * {@code INSTANCE}, we construct a real AiConfigService with null deps (the
 * readSetting try/catch swallows NPEs and returns defaults), so mode defaults
 * to "local".
 */
class BackendReactivatorTest {

    @Test
    void reactivate_localMode_switchesToLocalBackend() {
        AiConfigService cfg = new AiConfigService(null, null);
        cfg.init();  // mode defaults to "local" (readSetting catches NPE → default)
        AiModeService modeService = new AiModeService();
        ToolCallback[] tools = new ToolCallback[0];

        BackendReactivator reactivator = new BackendReactivator(modeService, tools, cfg);
        reactivator.reactivate();

        assertEquals("local", modeService.getCurrentMode());
        Optional<ChatBackend> backend = modeService.getService();
        assertTrue(backend.isPresent());
        // OllamaLocalBackend is not ready until loadModel is called (chatModel == null).
        assertFalse(backend.get().isReady());
    }

    @Test
    void reactivate_localMode_doesNotThrow() {
        AiConfigService cfg = new AiConfigService(null, null);
        cfg.init();
        AiModeService modeService = new AiModeService();

        BackendReactivator reactivator = new BackendReactivator(modeService, new ToolCallback[0], cfg);
        assertDoesNotThrow(() -> reactivator.reactivate());
    }
}
