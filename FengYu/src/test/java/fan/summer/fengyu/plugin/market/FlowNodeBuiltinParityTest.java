package fan.summer.fengyu.plugin.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural parity of the host flow-node resources: every key in the Chinese delta
 * ({@code flow-nodes/builtin_zh.json}) must address a real tool/port of the canonical
 * English catalog ({@code flow-nodes/builtin.json}), every canonical tool must be covered,
 * and every localized options array must carry exactly the canonical value set — a zh
 * dropdown missing a newly added operator (or inventing one) would change executable
 * behavior between locales.
 */
class FlowNodeBuiltinParityTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode builtin() throws Exception {
        return JSON.readTree(FlowNodeBuiltinParityTest.class
                .getResourceAsStream("/flow-nodes/builtin.json").readAllBytes());
    }

    private static JsonNode builtinZh() throws Exception {
        return JSON.readTree(FlowNodeBuiltinParityTest.class
                .getResourceAsStream("/flow-nodes/builtin_zh.json").readAllBytes());
    }

    @Test
    void zhDeltaAddressesOnlyRealToolsAndPortsAndCoversEveryTool() throws Exception {
        JsonNode canonical = builtin();
        JsonNode zh = builtinZh();
        Set<String> canonicalTools = new HashSet<>();
        for (JsonNode tool : canonical) {
            String toolId = tool.path("tool").asText();
            canonicalTools.add(toolId);
            Set<String> ports = new HashSet<>();
            tool.path("inputs").forEach(input -> ports.add(input.path("name").asText()));
            tool.path("outputs").forEach(output -> ports.add(output.path("name").asText()));

            JsonNode zhTool = zh.path(toolId);
            assertTrue(zhTool.isObject(), "no zh entry for canonical tool " + toolId);
            Set<String> zhPorts = new HashSet<>();
            zhTool.path("inputs").fieldNames().forEachRemaining(zhPorts::add);
            zhTool.path("outputs").fieldNames().forEachRemaining(zhPorts::add);
            assertTrue(canonicalPortsCovered(zhPorts, ports, toolId),
                    "zh ports of " + toolId + " must be a subset of the canonical ports");
        }
        zh.fieldNames().forEachRemaining(zhTool -> assertTrue(canonicalTools.contains(zhTool),
                "zh entry addresses an unknown tool: " + zhTool));
        assertEquals(canonicalTools.size(), zh.size(), "every canonical tool has a zh entry");
    }

    /** zh ports may localize a subset (display-only delta) but never name a nonexistent port. */
    private static boolean canonicalPortsCovered(Set<String> zhPorts, Set<String> ports, String tool) {
        for (String port : zhPorts) {
            if (!ports.contains(port)) {
                throw new AssertionError("zh addresses unknown port " + tool + "/" + port);
            }
        }
        return true;
    }

    @Test
    void localizedOptionsCarryExactlyTheCanonicalValueSet() throws Exception {
        for (JsonNode tool : builtin()) {
            String toolId = tool.path("tool").asText();
            JsonNode localized = ManifestI18n.localizeFlowNode(tool, builtinZh().path(toolId));
            for (JsonNode input : localized.path("inputs")) {
                JsonNode canonicalOptions = findInput(builtin(), toolId,
                        input.path("name").asText()).path("options");
                if (!canonicalOptions.isArray()) continue;
                JsonNode localizedOptions = input.path("options");
                assertTrue(localizedOptions.isArray(),
                        toolId + "/" + input.path("name").asText() + " lost its options");
                assertEquals(values(canonicalOptions), values(localizedOptions),
                        toolId + "/" + input.path("name").asText()
                                + " localized options must match the canonical values");
            }
        }
    }

    private static JsonNode findInput(JsonNode builtin, String toolId, String inputName) {
        for (JsonNode tool : builtin) {
            if (!toolId.equals(tool.path("tool").asText())) continue;
            for (JsonNode input : tool.path("inputs")) {
                if (inputName.equals(input.path("name").asText())) return input;
            }
        }
        throw new AssertionError("no canonical input " + toolId + "/" + inputName);
    }

    private static List<String> values(JsonNode options) {
        List<String> values = new ArrayList<>();
        for (JsonNode option : options) {
            values.add(option.isObject() ? option.path("value").asText() : option.asText());
        }
        return values;
    }
}
