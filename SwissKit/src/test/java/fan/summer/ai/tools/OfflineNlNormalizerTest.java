package fan.summer.ai.tools;

import fan.summer.api.ai.AiChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineNlNormalizerTest {

    @Test
    void normalize_mapsActionKeywordsToEnglish() {
        String out = OfflineNlNormalizer.normalize("请拆分这个文件");
        assertTrue(out.contains("split"), "action keyword must map to English; was: " + out);
    }

    @Test
    void normalize_preservesIdentifiersVerbatim() {
        String out = OfflineNlNormalizer.normalize("分析 /abs/data.xlsx 并按 Region 列拆分");
        assertTrue(out.contains("/abs/data.xlsx"), "file path must pass through; was: " + out);
        assertTrue(out.contains("Region"), "column name must pass through; was: " + out);
    }

    @Test
    void normalize_passesThroughUncoveredText() {
        assertEquals("plain english already", OfflineNlNormalizer.normalize("plain english already"));
    }

    @Test
    void normalizeLatestUser_rewritesOnlyLastUserMessage() {
        List<AiChatMessage> h = new ArrayList<>();
        h.add(AiChatMessage.user("拆分 a"));
        h.add(AiChatMessage.assistant("ok"));
        h.add(AiChatMessage.user("分析 b"));
        OfflineNlNormalizer.normalizeLatestUser(h);
        assertEquals("拆分 a", h.get(0).content());
        assertTrue(h.get(2).content().contains("analyze"));
    }
}
