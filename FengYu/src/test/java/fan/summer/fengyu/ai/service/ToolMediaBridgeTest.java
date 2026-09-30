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

    @Test
    void withoutMediaDegradesMediaMessagesToPlainText() {
        ToolResponseMessage tool = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call-1", "computer_screenshot", "{}"))).build();
        UserMessage media = UserMessage.builder()
                .text("Visual output attached from the preceding tool result.")
                .media(List.of(org.springframework.ai.content.Media.builder()
                        .mimeType(org.springframework.util.MimeTypeUtils.IMAGE_PNG)
                        .data(new org.springframework.core.io.ByteArrayResource(new byte[] {1}))
                        .name("shot.png")
                        .build()))
                .build();
        List<Message> conversation = List.of(new UserMessage("hi"), tool, media);

        assertTrue(ToolMediaBridge.containsMedia(conversation));
        List<Message> stripped = ToolMediaBridge.withoutMedia(conversation);
        assertEquals(3, stripped.size());
        // Non-media messages pass through untouched (same instances, same order).
        assertSame(conversation.get(0), stripped.get(0));
        assertSame(tool, stripped.get(1));
        UserMessage plain = (UserMessage) stripped.get(2);
        assertTrue(plain.getMedia().isEmpty());
        assertEquals("Visual output attached from the preceding tool result.", plain.getText());
        assertFalse(ToolMediaBridge.containsMedia(stripped));

        // A media message whose text is blank gets a placeholder instead of an empty string.
        UserMessage blank = UserMessage.builder().text("").media(List.of(
                org.springframework.ai.content.Media.builder()
                        .mimeType(org.springframework.util.MimeTypeUtils.IMAGE_PNG)
                        .data(new org.springframework.core.io.ByteArrayResource(new byte[] {1}))
                        .name("shot.png")
                        .build())).build();
        UserMessage replaced = (UserMessage) ToolMediaBridge.withoutMedia(List.of(blank)).get(0);
        assertTrue(replaced.getText().contains("omitted"));
    }

    @Test
    void detectsStrictGatewayMediaContentRejections() {
        // The exact error observed from a Go-based OpenAI-compatible gateway.
        assertTrue(ToolMediaBridge.isMediaContentRejection(new RuntimeException(
                "boom", new IllegalArgumentException(
                        "400: 请求体非法：json: cannot unmarshal array into Go struct field "
                                + "ChatMessage.messages.content of type string"))));
        assertTrue(ToolMediaBridge.isMediaContentRejection(new RuntimeException(
                "400: messages.2.content should be a string, but an array was provided")));
        assertTrue(ToolMediaBridge.isMediaContentRejection(new RuntimeException(
                "400: invalid content: expected string for messages[].content")));
        // Unrelated failures must NOT trigger the text-only fallback.
        assertFalse(ToolMediaBridge.isMediaContentRejection(new RuntimeException("401: invalid api key")));
        assertFalse(ToolMediaBridge.isMediaContentRejection(new RuntimeException(
                "400: Invalid type for 'messages[2].content[0]': expected object, got string")));
        assertFalse(ToolMediaBridge.isMediaContentRejection(null));
    }
}
