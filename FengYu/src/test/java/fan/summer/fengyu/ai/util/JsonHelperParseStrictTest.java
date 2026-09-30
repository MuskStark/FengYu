package fan.summer.fengyu.ai.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JsonHelper#parseObjectStrict} is the parser security decisions share with the tool
 * executor (Spring AI binds arguments through Jackson): duplicate keys, number shapes, and
 * malformed input must behave identically on both sides.
 */
class JsonHelperParseStrictTest {

    @Test
    void duplicateKeysResolveLastWinsLikeJackson() {
        Map<String, Object> parsed = JsonHelper.parseObjectStrict(
                "{\"command\":\"git status\",\"command\":\"rm -rf /\"}");
        assertEquals("rm -rf /", parsed.get("command"),
                "the guard must evaluate the key the executor binds (Jackson last-wins)");
    }

    @Test
    void numbersBindAsJacksonShapes() {
        Map<String, Object> parsed = JsonHelper.parseObjectStrict("{\"port\":24056,\"ratio\":1.5}");
        assertEquals(24056, parsed.get("port"));
        assertEquals(1.5, parsed.get("ratio"));
    }

    @Test
    void malformedInputThrowsInsteadOfBestEffortGuessing() {
        assertThrows(IllegalArgumentException.class,
                () -> JsonHelper.parseObjectStrict("{\"command\": \"unterminated"));
    }

    @Test
    void nullAndBlankReturnNullForCallersToDefault() {
        assertTrue(JsonHelper.parseObjectStrict(null) == null);
        assertTrue(JsonHelper.parseObjectStrict("   ") == null);
    }

    @Test
    void guardSeesTheSameCommandTheExecutorBinds() {
        // End-to-end shape: ToolPermissionRules.commandFromArguments uses the strict parser.
        String args = "{\"command\":\"echo hi && rm -rf /tmp/x\"}";
        assertEquals("echo hi && rm -rf /tmp/x",
                fan.summer.fengyu.ai.tools.ToolPermissionRules.commandFromArguments(args));
        // A non-string command leaves rules on whole-string matching (fail-safe, unchanged).
        assertEquals("{\"command\":123}",
                fan.summer.fengyu.ai.tools.ToolPermissionRules.commandFromArguments("{\"command\":123}"));
    }
}
