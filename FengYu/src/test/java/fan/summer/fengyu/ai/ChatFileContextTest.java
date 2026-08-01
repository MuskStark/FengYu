package fan.summer.fengyu.ai;

import fan.summer.fengyu.plugin.runtime.PluginFileGrantService.FileRef;
import fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatFileContextTest {

    @AfterEach
    void cleanThread() {
        ChatFileContext.clear();
    }

    @Test
    void currentIsEmptyBeforeSet() {
        assertTrue(ChatFileContext.current().isEmpty(),
            "current() must return an empty list, not null, before anything is set");
    }

    @Test
    void setMakesRefsVisibleToCurrent() {
        ActiveFileRef ref = new ActiveFileRef("fan.summer.excel",
            new FileRef("ref_abc", "report.xlsx", "file", "read", 123L));
        ChatFileContext.set(List.of(ref));
        assertEquals(1, ChatFileContext.current().size());
        assertEquals("fan.summer.excel", ChatFileContext.current().get(0).pluginId());
    }

    @Test
    void setNullIsTreatedAsEmpty() {
        ChatFileContext.set(null);
        assertTrue(ChatFileContext.current().isEmpty());
    }

    @Test
    void clearRemovesRefs() {
        ChatFileContext.set(List.of(new ActiveFileRef("p", new FileRef("ref_x", "f", "file", "read", 1L))));
        ChatFileContext.clear();
        assertTrue(ChatFileContext.current().isEmpty());
    }
}
