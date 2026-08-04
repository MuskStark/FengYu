package fan.summer.fengyu.plugin.store;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the bounded-response helper used by catalog adapters so a malicious or broken catalog
 * URL cannot OOM the backend by streaming an unbounded body into memory.
 */
class BoundedHttpTest {

    @Test
    void readsBodyUnderTheLimit() throws IOException {
        byte[] body = "{\"plugins\":[]}".getBytes(StandardCharsets.UTF_8);
        String result = BoundedHttp.readAtMost(new ByteArrayInputStream(body), body.length + 1);
        assertEquals(new String(body, StandardCharsets.UTF_8), result);
    }

    @Test
    void readsBodyExactlyAtTheLimit() throws IOException {
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        String result = BoundedHttp.readAtMost(new ByteArrayInputStream(body), 5);
        assertEquals("hello", result);
    }

    @Test
    void rejectsBodyExceedingTheLimit() {
        byte[] body = new byte[100];
        // one byte over the cap must throw, never return a partial/giant buffer
        IOException ex = assertThrows(IOException.class,
            () -> BoundedHttp.readAtMost(new ByteArrayInputStream(body), 99));
        assertTrue(ex.getMessage().contains("exceeds") || ex.getMessage().contains("limit"),
            "should mention the size limit; got: " + ex.getMessage());
    }

    @Test
    void readsEmptyBody() throws IOException {
        assertEquals("", BoundedHttp.readAtMost(new ByteArrayInputStream(new byte[0]), 16));
    }
}
