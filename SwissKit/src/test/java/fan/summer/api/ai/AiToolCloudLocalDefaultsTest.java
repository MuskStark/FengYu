package fan.summer.zhiflow.api.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiToolCloudLocalDefaultsTest {

    /** Minimal AiTool that relies entirely on defaults for the 4 new methods. */
    private static final AiTool DEFAULTS = new AiTool() {
        public String getName() { return "defaults"; }
        public String getDescription() { return "cloud-desc"; }
        public List<AiToolParam> getParameters() {
            return List.of(AiToolParam.of("p", "string", "param"));
        }
        public AiToolResult execute(Map<String, Object> args) { return AiToolResult.success("ok"); }
    };

    @Test
    void localDescriptionFallsBackToCloud() {
        assertEquals("cloud-desc", DEFAULTS.getLocalDescription());
    }

    @Test
    void localParametersFallBackToCloud() {
        assertEquals(DEFAULTS.getParameters(), DEFAULTS.getLocalParameters());
    }

    @Test
    void supportsLocalDefaultsTrue() {
        assertTrue(DEFAULTS.supportsLocal());
    }

    @Test
    void supportsCloudDefaultsTrue() {
        assertTrue(DEFAULTS.supportsCloud());
    }

    @Test
    void canOverrideAllFour() {
        AiTool custom = new AiTool() {
            public String getName() { return "custom"; }
            public String getDescription() { return "cloud"; }
            public String getLocalDescription() { return "local"; }
            public List<AiToolParam> getParameters() { return List.of(); }
            public List<AiToolParam> getLocalParameters() { return List.of(); }
            public boolean supportsLocal() { return false; }
            public boolean supportsCloud() { return true; }
            public AiToolResult execute(Map<String, Object> args) { return AiToolResult.success("ok"); }
        };
        assertEquals("local", custom.getLocalDescription());
        assertTrue(custom.getLocalParameters().isEmpty());
        assertFalse(custom.supportsLocal());
        assertTrue(custom.supportsCloud());
    }
}
