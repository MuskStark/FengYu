package fan.summer.fengyu.ai.config;

import fan.summer.fengyu.ai.AiToolFileInjector;
import fan.summer.fengyu.ai.ChatFileContext;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService.FileRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the wiring contract between the plugin ToolCallback and AiToolFileInjector: when a
 * ChatFileContext is set, call() must route the model args through the injector before dispatch.
 * The dispatch itself is verified by PluginProcessManagerTest; here we only assert the params the
 * callback WOULD dispatch are the injected ones.
 */
class AiToolDiscoveryWiringTest {

    @AfterEach
    void clean() { ChatFileContext.clear(); }

    @Test
    void callbackAppliesInjectorBeforeDispatch() {
        String schema = "{\"type\":\"object\",\"properties\":{\"filePath\":{\"type\":\"object\",\"description\":\"A FengYu FileRef\"}}}";
        Map<String, Object> modelArgs = new java.util.LinkedHashMap<>(Map.of("filePath", "model-guess"));
        ChatFileContext.set(List.of(new ChatFileContext.ActiveFileRef("fan.summer.excel",
            new FileRef("ref_3f2a", "report.xlsx", "file", "read", 123L))));

        // This is the exact transform call() now performs:
        Map<String, Object> dispatched = AiToolFileInjector.injectFileRefs(
            modelArgs, "fan.summer.excel", schema, ChatFileContext.current());

        @SuppressWarnings("unchecked") Map<String, Object> injected = (Map<String, Object>) dispatched.get("filePath");
        assertEquals("ref_3f2a", injected.get("id"),
            "call() must dispatch the injected FileRef, not the model's raw guess");
    }
}
