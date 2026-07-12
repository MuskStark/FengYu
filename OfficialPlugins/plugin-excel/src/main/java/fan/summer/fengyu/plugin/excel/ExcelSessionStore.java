package fan.summer.fengyu.plugin.excel;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** In-memory per-session {@link SplitConfig} store; also tracks the most-recently-touched
 *  session so stateless AI tools can operate on the "current" workflow. */
@Component
public class ExcelSessionStore {

    private final ConcurrentHashMap<String, SplitConfig> byId = new ConcurrentHashMap<>();
    private final AtomicReference<String> activeId = new AtomicReference<>();

    public SplitConfig get(String session) {
        SplitConfig c = byId.computeIfAbsent(session, k -> new SplitConfig());
        activeId.set(session);
        return c;
    }

    public void markActive(String session) { activeId.set(session); }

    public Optional<SplitConfig> active() {
        String id = activeId.get();
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public void remove(String session) {
        byId.remove(session);
        activeId.compareAndSet(session, null);
    }
}
