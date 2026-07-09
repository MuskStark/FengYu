package fan.summer.zhiflow.web.controller;

import fan.summer.zhiflow.ai.service.AiConfigServiceHeadless;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * UI-shell settings — theme, language, sidebar-collapsed. Backed by
 * {@link AiConfigServiceHeadless} (a bean, JPA-persisted, user-scoped). {@code GET} returns the
 * current values; {@code PUT} accepts a partial JSON object and persists only the keys present.
 *
 * <p>Injects the bean to make the wiring explicit; reads/writes go through the bean's facade.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final AiConfigServiceHeadless config;

    public SettingsController(AiConfigServiceHeadless config) {
        this.config = config;
    }

    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> out = new HashMap<>();
        out.put("theme", config.getTheme());
        out.put("language", config.getLanguage());
        out.put("sidebarCollapsed", config.getSidebarCollapsed());
        return out;
    }

    @PutMapping
    public Map<String, Object> put(@RequestBody Map<String, Object> body) {
        if (body.get("theme") instanceof String t) {
            config.setTheme(t);
        }
        if (body.get("language") instanceof String l) {
            config.setLanguage(l);
        }
        Object collapsed = body.get("sidebarCollapsed");
        if (collapsed instanceof Boolean b) {
            config.setSidebarCollapsed(b);
        } else if (collapsed instanceof String s) {
            config.setSidebarCollapsed(Boolean.parseBoolean(s));
        }
        return get();
    }
}
