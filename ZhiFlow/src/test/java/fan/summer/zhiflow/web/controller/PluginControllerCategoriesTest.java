package fan.summer.zhiflow.web.controller;

import fan.summer.zhiflow.api.ToolCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PluginControllerCategoriesTest {

    @Test
    void categoriesEndpointReturnsAllEnums() {
        PluginController controller = new PluginController(null); // registry unused for categories
        @SuppressWarnings("unchecked")
        List<Map<String, String>> result = (List<Map<String, String>>) controller.categories();
        assertEquals(ToolCategory.values().length, result.size());
        // every entry has the 3 keys
        for (Map<String, String> entry : result) {
            assertEquals(3, entry.size(), entry.toString());
            assertNotNull(entry.get("id"));
            assertTrue(entry.get("labelKey").startsWith("category."));
            assertNotNull(entry.get("icon"));
        }
        // AI is present
        assertTrue(result.stream().anyMatch(e -> "ai".equals(e.get("id"))));
    }
}
