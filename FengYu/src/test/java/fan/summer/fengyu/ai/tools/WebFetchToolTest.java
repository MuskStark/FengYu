package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebFetchToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void returnsReadableBoundedPageTextAsReadOnlyTool() throws Exception {
        WebTextClient client = (url, max) -> new WebTextClient.WebResponse(
                "https://example.com/final", 200, "text/html",
                "<html><title>Example &amp; Docs</title><script>ignore()</script><body><h1>Hello</h1><p>World</p></body></html>");
        WebFetchTool tool = new WebFetchTool(client);

        Map<?, ?> result = JSON.readValue(tool.fetch("https://example.com", 16), Map.class);
        assertEquals(Boolean.TRUE, result.get("success"));
        assertEquals("Example & Docs", result.get("title"));
        assertEquals(16, ((String) result.get("text")).length());
        assertFalse(((String) result.get("text")).contains("ignore"));
        assertEquals(Boolean.TRUE, result.get("truncated"));
        assertEquals(ToolEffect.READ, tool.effectFor("web_fetch"));
    }

    @Test
    void rejectsLocalAndNonHttpTargets() {
        assertThrows(IllegalArgumentException.class, () -> SafeWebTextClient.checkedUri("file:///tmp/a"));
        assertThrows(Exception.class,
                () -> SafeWebTextClient.assertPublicHost(URI.create("http://127.0.0.1/private")));
    }

    /**
     * IPv6 forms that embed IPv4 targets must not smuggle private addresses past the
     * policy: IPv4-mapped {@code ::ffff:a.b.c.d} and the NAT64 well-known prefix
     * {@code 64:ff9b::/96} are decided by (respectively blocked on) their embedded or
     * translated IPv4 address, while a genuinely public IPv6 literal still passes.
     */
    @Test
    void rejectsIpv4MappedAndNat64FormsOfPrivateAddresses() {
        // IPv4-mapped loopback (the parser usually unmasks it; both shapes must be caught).
        assertThrows(Exception.class,
                () -> SafeWebTextClient.assertPublicHost(URI.create("http://[::ffff:127.0.0.1]/x")));
        // IPv4-mapped link-local metadata service.
        assertThrows(Exception.class,
                () -> SafeWebTextClient.assertPublicHost(URI.create("http://[::ffff:169.254.169.254]/x")));
        // NAT64 well-known prefix wrapping loopback — the whole prefix is blocked.
        assertThrows(Exception.class,
                () -> SafeWebTextClient.assertPublicHost(URI.create("http://[64:ff9b::127.0.0.1]/x")));
        // The ENTIRE translation prefix is blocked even when it wraps a public IPv4 —
        // the documented policy (the prefix itself is an IPv4-in-disguise signal).
        assertThrows(Exception.class,
                () -> SafeWebTextClient.assertPublicHost(URI.create("http://[64:ff9b::8.8.8.8]/x")));
        // A public IPv6 literal is unaffected.
        assertDoesNotThrow(
                () -> SafeWebTextClient.assertPublicHost(URI.create("http://[2606:4700::6810:85e5]/x")));
    }
}
