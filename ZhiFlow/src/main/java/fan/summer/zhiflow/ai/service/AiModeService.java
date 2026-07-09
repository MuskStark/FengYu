package fan.summer.zhiflow.ai.service;

import fan.summer.zhiflow.api.ai.ChatBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Backend mode management (the non-tool half of the former AiServiceProvider).
 * Holds the active {@link ChatBackend} + mode label and notifies listeners on switch.
 * Tool registry responsibilities are gone — Spring AI discovers tools itself.
 */
@Component
public class AiModeService {

    private static final Logger log = LoggerFactory.getLogger(AiModeService.class);

    private volatile ChatBackend activeBackend;
    private volatile String mode = "local";
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public Optional<ChatBackend> getService() { return Optional.ofNullable(activeBackend); }

    public String getCurrentMode() { return mode; }

    public synchronized void switchMode(String mode, ChatBackend newBackend) {
        if (activeBackend != null) {
            try { activeBackend.unloadModel(); }
            catch (Exception e) { log.warn("Failed to unload previous backend: {}", e.getMessage()); }
        }
        this.mode = mode;
        this.activeBackend = newBackend;
        for (Runnable l : listeners) l.run();
    }

    public void setService(ChatBackend backend) { this.activeBackend = backend; }

    public void addOnStateChangeListener(Runnable l) { listeners.add(l); }
    public void removeOnStateChangeListener(Runnable l) { listeners.remove(l); }
}
