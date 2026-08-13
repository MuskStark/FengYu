package fan.summer.fengyu.ai.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolMediaBridgeTest {

    @Test
    void turnsScreenshotBase64IntoMediaAndSanitizesToolText() {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
        String encoded = Base64.getEncoder().encodeToString(png);
        ToolResponseMessage tool = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call-1", "browser_screenshot",
                        "{\"success\":true,\"mimeType\":\"image/png\",\"imageBase64\":\""
                                + encoded + "\",\"width\":1,\"height\":1}"))).build();

        ToolMediaBridge.Result result = ToolMediaBridge.extract(List.of(tool));

        assertEquals(2, result.messages().size());
        ToolResponseMessage sanitized = (ToolResponseMessage) result.messages().getFirst();
        assertFalse(sanitized.getResponses().getFirst().responseData().contains("imageBase64"));
        assertTrue(sanitized.getResponses().getFirst().responseData().contains("imageAttached"));
        UserMessage image = (UserMessage) result.messages().get(1);
        assertEquals(1, image.getMedia().size());
        assertArrayEquals(png, image.getMedia().getFirst().getDataAsByteArray());
        assertEquals(1, result.lastResponseMedia().getFirst().size());
    }

    @Test
    void leavesInvalidPngAsTextOnlyResult() {
        String encoded = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
        Message tool = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call-1", "browser_screenshot",
                        "{\"mimeType\":\"image/png\",\"imageBase64\":\"" + encoded + "\"}"))).build();
        ToolMediaBridge.Result result = ToolMediaBridge.extract(List.of(tool));
        assertEquals(1, result.messages().size());
        assertTrue(result.lastResponseMedia().getFirst().isEmpty());
    }
}
