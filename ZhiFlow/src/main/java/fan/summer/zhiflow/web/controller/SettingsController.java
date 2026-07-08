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
 * UI-shell settings — theme, language, sidebar-collapsed. H2-backed via
 * {@link AiConfigServiceHeadless}. {@code GET} returns the current values; {@code PUT} accepts a
 * partial JSON object and persists only the keys present.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> out = new HashMap<>();
        out.put("theme", AiConfigServiceHeadless.getTheme());
        out.put("language", AiConfigServiceHeadless.getLanguage());
        out.put("sidebarCollapsed", AiConfigServiceHeadless.getSidebarCollapsed());
        return out;
    }

    @PutMapping
    public Map<String, Object> put(@RequestBody Map<String, Object> body) {
        if (body.get("theme") instanceof String t) {
            AiConfigServiceHeadless.setTheme(t);
        }
        if (body.get("language") instanceof String l) {
            AiConfigServiceHeadless.setLanguage(l);
        }
        Object collapsed = body.get("sidebarCollapsed");
        if (collapsed instanceof Boolean b) {
            AiConfigServiceHeadless.setSidebarCollapsed(b);
        } else if (collapsed instanceof String s) {
            AiConfigServiceHeadless.setSidebarCollapsed(Boolean.parseBoolean(s));
        }
        return get();
    }
}
