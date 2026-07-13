package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.api.ToolCategory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared category vocabulary; plugin discovery itself lives in {@link PluginRuntimeController}. */
@RestController
public class PluginController {
    @GetMapping("/api/plugin-categories")
    public List<Map<String, String>> categories() {
        List<Map<String, String>> out = new ArrayList<>();
        for (ToolCategory category : ToolCategory.values()) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("id", category.getId());
            entry.put("labelKey", category.getLabelKey());
            entry.put("icon", "puzzle-outline");
            out.add(entry);
        }
        return out;
    }
}
