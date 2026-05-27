package fan.summer.api.ai;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AiServiceProvider {

    private static volatile AiService instance;
    private static volatile String currentMode = "local";
    private static final List<Runnable> stateChangeListeners = new CopyOnWriteArrayList<>();
    private static final Map<String, AiTool> tools = new ConcurrentHashMap<>();

    private AiServiceProvider() {}

    // ── Service management ────────────────────────────────────

    public static void setService(AiService service) {
        instance = service;
    }

    public static Optional<AiService> getService() {
        return Optional.ofNullable(instance);
    }

    public static synchronized void switchMode(String mode, AiService newService) {
        if (instance != null) {
            try {
                instance.unloadModel();
            } catch (Exception e) {
                Logger.getLogger(AiServiceProvider.class.getName())
                    .log(Level.WARNING, "Failed to unload previous AI service", e);
            }
        }
        currentMode = mode;
        instance = newService;
        notifyStateChanged();
    }

    // ── State change listeners ────────────────────────────────

    public static void addOnStateChangeListener(Runnable listener) {
        stateChangeListeners.add(listener);
    }

    public static void removeOnStateChangeListener(Runnable listener) {
        stateChangeListeners.remove(listener);
    }

    public static void notifyStateChanged() {
        for (Runnable listener : stateChangeListeners) {
            listener.run();
        }
    }

    // ── Mode ──────────────────────────────────────────────────

    public static String getCurrentMode() {
        return currentMode;
    }

    public static void setCurrentMode(String mode) {
        currentMode = mode;
    }

    // ── Shared tool registry ──────────────────────────────────

    public static void registerTool(AiTool tool) {
        tools.put(tool.getName(), tool);
    }

    public static void unregisterTool(String name) {
        tools.remove(name);
    }

    public static List<AiTool> getTools() {
        return List.copyOf(tools.values());
    }

    public static boolean hasTools() {
        return !tools.isEmpty();
    }

    public static AiTool getTool(String name) {
        return tools.get(name);
    }
}
