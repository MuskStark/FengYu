package fan.summer.fengyu.ai;

import fan.summer.fengyu.plugin.runtime.PluginFileGrantService.FileRef;
import fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * Mirrors the runtime model: the request thread sets the refs, spawns a virtual child thread
     * to run the tool loop, then clears on the request thread. The child MUST still see the refs —
     * a plain ThreadLocal fails this (returns empty), which is why the store is an
     * InheritableThreadLocal.
     */
    @Test
    void childThreadSeesRefsAfterParentClears() throws InterruptedException {
        ActiveFileRef ref = new ActiveFileRef("fan.summer.excel",
            new FileRef("ref_child", "report.xlsx", "file", "read", 1L));
        ChatFileContext.set(List.of(ref));

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch parentCleared = new CountDownLatch(1);
        AtomicReference<String> seenInChild = new AtomicReference<>("<not-run>");

        // Simulate startChat: spawn the tool-loop thread AFTER set, BEFORE clear.
        Thread child = Thread.ofVirtual().start(() -> {
            started.countDown();
            try {
                parentCleared.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // Read AFTER the parent has cleared — must still hold the inherited copy.
            var current = ChatFileContext.current();
            seenInChild.set(current.isEmpty() ? "<empty>" : current.get(0).pluginId());
        });
        assertTrue(started.await(2, TimeUnit.SECONDS), "child thread did not start");

        // Simulate the request-thread finally: clear while the child is still running.
        ChatFileContext.clear();
        parentCleared.countDown();

        child.join(2_000);
        assertEquals("fan.summer.excel", seenInChild.get(),
            "child (tool-loop) thread must inherit the refs and keep them after the parent clears; " +
            "a plain ThreadLocal would read <empty> here, silently breaking route B injection");
    }
}
