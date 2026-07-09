package fan.summer.zhiflow.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolCategoryTest {

    @Test
    void hasAiCategory() {
        assertEquals("ai", ToolCategory.AI.getId());
        assertEquals("category.ai", ToolCategory.AI.getLabelKey());
    }

    @Test
    void allLabelKeysUseUnifiedPrefix() {
        for (ToolCategory c : ToolCategory.values()) {
            assertTrue(c.getLabelKey().startsWith("category."),
                "labelKey " + c.getLabelKey() + " should start with 'category.'");
        }
    }

    @Test
    void fromIdResolvesAi() {
        assertEquals(ToolCategory.AI, ToolCategory.fromId("ai"));
    }
}
