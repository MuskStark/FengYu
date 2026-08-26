package fan.summer.fengyu.web.filter;

import fan.summer.fengyu.HeadlessLauncher;
import fan.summer.fengyu.web.StreamTicketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenAuthFilterTest {

    private final StreamTicketService tickets = new StreamTicketService();
    private final TokenAuthFilter filter = new TokenAuthFilter(tickets);

    @AfterEach
    void clearToken() {
        System.clearProperty(HeadlessLauncher.TOKEN_PROPERTY);
    }

    /** Real HTTP/1.1 clients always send a loopback Host here; MockHttpServletRequest does not. */
    private static MockHttpServletRequest loopback(MockHttpServletRequest request) {
        request.addHeader("Host", "127.0.0.1:24056");
        return request;
    }

    @Test
    void allowsSandboxedPluginUiAssetsWithoutAHeaderToken() throws Exception {
        System.setProperty(HeadlessLauncher.TOKEN_PROPERTY, "desktop-token");
        var request = loopback(
                new MockHttpServletRequest("GET", "/plugin-runtime/fan.summer.markdown/ui/index.html"));
        var response = new MockHttpServletResponse();
        var invoked = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertTrue(invoked.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    void stillRejectsProtectedApiRequestsWithoutAHeaderToken() throws Exception {
        System.setProperty(HeadlessLauncher.TOKEN_PROPERTY, "desktop-token");
        var request = loopback(new MockHttpServletRequest("GET", "/api/plugin-runtime"));
        var response = new MockHttpServletResponse();
        var invoked = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertFalse(invoked.get());
        assertEquals(401, response.getStatus());
    }

    @Test
    void onlyWebhookDeliveryPostsBypassTheLaunchToken() throws Exception {
        System.setProperty(HeadlessLauncher.TOKEN_PROPERTY, "desktop-token");
        var delivery = loopback(new MockHttpServletRequest("POST", "/api/workflow-hooks/hook-1"));
        var response = new MockHttpServletResponse();
        var invoked = new AtomicBoolean();

        filter.doFilter(delivery, response, (req, res) -> invoked.set(true));

        assertTrue(invoked.get(), "the controller performs independent per-trigger auth");

        assertRejected(loopback(new MockHttpServletRequest("GET", "/api/workflow-hooks/hook-1")));
        assertRejected(loopback(new MockHttpServletRequest("POST", "/api/agent/webhook-triggers")));
    }

    @Test
    void agentEventSourceRedeemsAOneTimeTicket() throws Exception {
        System.setProperty(HeadlessLauncher.TOKEN_PROPERTY, "desktop-token");
        String ticket = tickets.issue(StreamTicketService.AGENT_STREAM_ENDPOINT).ticket();

        var request = loopback(new MockHttpServletRequest("GET", "/api/agent/stream"));
        request.setParameter("runId", "run-1");
        request.setParameter("ticket", ticket);
        var response = new MockHttpServletResponse();
        var invoked = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertTrue(invoked.get(), "a freshly minted ticket must open the stream");
        assertEquals(200, response.getStatus());
    }

    @Test
    void agentEventSourceTicketIsSingleUse() throws Exception {
        System.setProperty(HeadlessLauncher.TOKEN_PROPERTY, "desktop-token");
        String ticket = tickets.issue(StreamTicketService.AGENT_STREAM_ENDPOINT).ticket();
        redeemOnce(requestWithTicket(ticket));

        var replay = requestWithTicket(ticket);
        var response = new MockHttpServletResponse();
        var invoked = new AtomicBoolean();
        filter.doFilter(replay, response, (req, res) -> invoked.set(true));

        assertFalse(invoked.get(), "a replayed ticket must be rejected");
        assertEquals(401, response.getStatus());
    }

    /** A ticket minted for the AI stream must not open the agent stream (and vice versa). */
    @Test
    void streamTicketRedeemsOnlyOnItsBoundEndpoint() throws Exception {
        System.setProperty(HeadlessLauncher.TOKEN_PROPERTY, "desktop-token");
        String aiTicket = tickets.issue(StreamTicketService.AI_STREAM_ENDPOINT).ticket();
        var onAgentStream = loopback(new MockHttpServletRequest("GET", "/api/agent/stream"));
        onAgentStream.setParameter("runId", "run-1");
        onAgentStream.setParameter("ticket", aiTicket);
        assertRejected(onAgentStream);

        String agentTicket = tickets.issue(StreamTicketService.AGENT_STREAM_ENDPOINT).ticket();
        var onAiStream = loopback(new MockHttpServletRequest("GET", "/api/ai/stream"));
        onAiStream.setParameter("streamId", "s-1");
        onAiStream.setParameter("ticket", agentTicket);
        assertRejected(onAiStream);
    }

    @Test
    void streamEndpointsRejectTheQueryTokenAndUnknownTickets() throws Exception {
        System.setProperty(HeadlessLauncher.TOKEN_PROPERTY, "desktop-token");
        // The historical ?token= fallback leaked the full credential into URL logs — it is gone.
        var withToken = loopback(new MockHttpServletRequest("GET", "/api/ai/stream"));
        withToken.setParameter("streamId", "s-1");
        withToken.setParameter("token", "desktop-token");
        assertRejected(withToken);

        var withUnknownTicket = loopback(new MockHttpServletRequest("GET", "/api/ai/stream"));
        withUnknownTicket.setParameter("streamId", "s-1");
        withUnknownTicket.setParameter("ticket", "forged");
        assertRejected(withUnknownTicket);

        var bare = loopback(new MockHttpServletRequest("GET", "/api/agent/stream"));
        bare.setParameter("runId", "run-1");
        assertRejected(bare);
    }

    /**
     * DNS-rebinding firewall: a site that rebinds its domain to 127.0.0.1 addresses us with its
     * own Host header. The check precedes every exemption and even a valid launch token — it
     * gates the token-off (dev) posture just as hard.
     */
    @Test
    void rejectsNonLoopbackHostHeadersBeforeEveryExemption() throws Exception {
        System.setProperty(HeadlessLauncher.TOKEN_PROPERTY, "desktop-token");

        var rebinding = new MockHttpServletRequest("GET", "/api/plugin-runtime");
        rebinding.addHeader("Host", "attacker.example:24056");
        rebinding.addHeader("X-FengYu-Token", "desktop-token");
        assertForbidden(rebinding);

        var noHost = new MockHttpServletRequest("GET", "/api/health");
        assertForbidden(noHost);

        // The dev proxy (changeOrigin) and the desktop webview both address the loopback names.
        for (String host : new String[] {"127.0.0.1", "127.0.0.1:24056", "localhost", "localhost:24056", "[::1]:24056"}) {
            var request = new MockHttpServletRequest("GET", "/api/health");
            request.addHeader("Host", host);
            var response = new MockHttpServletResponse();
            var invoked = new AtomicBoolean();
            filter.doFilter(request, response, (req, res) -> invoked.set(true));
            assertTrue(invoked.get(), "loopback Host '" + host + "' must pass");
        }
    }

    /** With auth configured, the setup wizard rides the same launch token as everything else. */
    @Test
    void setupEndpointsRequireTheLaunchTokenWhenAuthIsConfigured() throws Exception {
        System.setProperty(HeadlessLauncher.TOKEN_PROPERTY, "desktop-token");

        var anonymous = loopback(new MockHttpServletRequest("POST", "/api/setup/initialize"));
        assertRejected(anonymous);

        var authorized = loopback(new MockHttpServletRequest("POST", "/api/setup/initialize"));
        authorized.addHeader("X-FengYu-Token", "desktop-token");
        var response = new MockHttpServletResponse();
        var invoked = new AtomicBoolean();
        filter.doFilter(authorized, response, (req, res) -> invoked.set(true));
        assertTrue(invoked.get(), "the desktop wizard attaches the launch token");

        // Browser-dev (no token configured): the first-launch wizard stays reachable.
        System.clearProperty(HeadlessLauncher.TOKEN_PROPERTY);
        var dev = loopback(new MockHttpServletRequest("POST", "/api/setup/initialize"));
        var devResponse = new MockHttpServletResponse();
        var devInvoked = new AtomicBoolean();
        filter.doFilter(dev, devResponse, (req, res) -> devInvoked.set(true));
        assertTrue(devInvoked.get(), "with auth disabled the wizard remains open");
    }

    private MockHttpServletRequest requestWithTicket(String ticket) {
        var request = loopback(new MockHttpServletRequest("GET", "/api/agent/stream"));
        request.setParameter("runId", "run-1");
        request.setParameter("ticket", ticket);
        return request;
    }

    private void redeemOnce(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { /* consumed */ });
    }

    private void assertRejected(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        var invoked = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> invoked.set(true));
        assertFalse(invoked.get());
        assertEquals(401, response.getStatus());
    }

    private void assertForbidden(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        var invoked = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> invoked.set(true));
        assertFalse(invoked.get());
        assertEquals(403, response.getStatus());
    }
}
