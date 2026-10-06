package fan.summer.fengyu.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.ai.tools.WorkspaceFileTools;
import fan.summer.fengyu.ai.workspace.WorkspaceContext;
import fan.summer.fengyu.ai.workspace.WorkspaceReadState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end pin of the read_file image path: a REAL {@code readFile} envelope (magic-byte
 * verified, vision-gated) must survive {@link ToolMediaBridge#extract} — the sanitized
 * tool text replaces the base64 and a multimodal {@link UserMessage} carries the decoded
 * bytes the model sees. Without this pin the two halves can drift apart by key name.
 */
class ToolMediaBridgeReadImageTest {

    private static final Long CONVERSATION = 77L;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Minimal 1×1 transparent PNG (real magic bytes). */
    private static final byte[] ONE_PIXEL_PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    @TempDir
    Path root;

    @BeforeEach
    void bind() {
        WorkspaceContext.set(new WorkspaceContext.Binding(root, CONVERSATION));
        // Vision-capable active model (openai + gpt-4o) so read_file emits the envelope.
        Map<String, String> settings = new HashMap<>();
        settings.put("ai.mode", "openai");
        settings.put("ai.openai.model", "gpt-4o");
        var repo = mock(fan.summer.fengyu.database.repository.AppSettingRepository.class);
        when(repo.findByUserIdAndSettingKey(anyLong(), any())).thenAnswer(invocation -> {
            String value = settings.get(invocation.getArgument(1));
            if (value == null) return java.util.Optional.empty();
            var entity = new fan.summer.fengyu.database.entity.AppSettingEntity();
            entity.setSettingKey(invocation.getArgument(1));
            entity.setSettingValue(value);
            return java.util.Optional.of(entity);
        });
        new fan.summer.fengyu.ai.AiConfigService(repo,
                new fan.summer.fengyu.security.NoopSecurityContext()).init();
    }

    @AfterEach
    void unbind() {
        WorkspaceContext.clear();
        new fan.summer.fengyu.ai.AiConfigService(
                mock(fan.summer.fengyu.database.repository.AppSettingRepository.class),
                new fan.summer.fengyu.security.NoopSecurityContext()).init();
    }

    @Test
    void readFileImageEnvelopeBecomesMultimodalUserMessage() throws Exception {
        Files.write(root.resolve("pixel.png"), ONE_PIXEL_PNG);
        String envelope = new WorkspaceFileTools(new WorkspaceReadState())
                .readFile("pixel.png", null, null);

        JsonNode parsed = JSON.readTree(envelope);
        assertTrue(parsed.path("success").asBoolean(), envelope);
        assertEquals("image/png", parsed.path("mimeType").asText());
        assertFalse(parsed.path("imageBase64").asText().isEmpty());

        ToolMediaBridge.Result result = ToolMediaBridge.extract(List.of(
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("call-img", "read_file", envelope)))
                        .build()));

        assertEquals(2, result.messages().size(), "sanitized tool response + media part");
        ToolResponseMessage sanitized = (ToolResponseMessage) result.messages().getFirst();
        assertFalse(sanitized.getResponses().getFirst().responseData().contains("imageBase64"));
        assertTrue(sanitized.getResponses().getFirst().responseData().contains("imageAttached"));
        UserMessage media = (UserMessage) result.messages().get(1);
        assertEquals(1, media.getMedia().size());
        assertArrayEquals(ONE_PIXEL_PNG, media.getMedia().getFirst().getDataAsByteArray());
        assertEquals(1, result.lastResponseMedia().getFirst().size());
    }

    @Test
    void misnamedImageFilesNeverReachTheBridgeEnvelope() throws Exception {
        // A .png whose content is not a PNG must fail read_file itself (magic-byte check)
        // — before any base64 can bloat a tool result the bridge would pass through.
        Files.write(root.resolve("fake.png"), new byte[] {1, 2, 3, 4});
        String envelope = new WorkspaceFileTools(new WorkspaceReadState())
                .readFile("fake.png", null, null);

        JsonNode parsed = JSON.readTree(envelope);
        assertFalse(parsed.path("success").asBoolean());
        assertTrue(parsed.path("error").asText().contains("magic bytes"));
    }
}
