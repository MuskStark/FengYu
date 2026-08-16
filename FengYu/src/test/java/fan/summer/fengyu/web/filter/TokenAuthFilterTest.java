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

    @Test
    void allowsSandboxedPluginUiAssetsWithoutAHeaderToken() throws Exception {
        System.setProperty(HeadlessLauncher.TOKEN_PROPERTY, "desktop-token");
        var request = new MockHttpServletRequest("GET", "/plugin-runtime/fan.summer.markdown/ui/index.html");
        var response = new MockHttpServletResponse();
        var invoked = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertTrue(invoked.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    void stillRejectsProtectedApiRequestsWithoutAHeaderToken() throws Exception {
        System.setProperty(HeadlessLauncher.TOKEN_PROPERTY, "desktop-token");
        var request = new MockHttpServletRequest("GET", "/api/plugin-runtime");
        var response = new MockHttpServletResponse();
        var invoked = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertFalse(invoked.get());
        assertEquals(401, response.getStatus());
    }

    @Test
    void agentEventSourceRedeemsAOneTimeTicket() throws Exception {
        System.setProperty(HeadlessLauncher.TOKEN_PROPERTY, "desktop-token");
        String ticket = tickets.issue(StreamTicketService.AGENT_STREAM_ENDPOINT).ticket();

        var request = new MockHttpServletRequest("GET", "/api/agent/stream");
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
        var onAgentStream = new MockHttpServletRequest("GET", "/api/agent/stream");
        onAgentStream.setParameter("runId", "run-1");
        onAgentStream.setParameter("ticket", aiTicket);
        assertRejected(onAgentStream);

        String agentTicket = tickets.issue(StreamTicketService.AGENT_STREAM_ENDPOINT).ticket();
        var onAiStream = new MockHttpServletRequest("GET", "/api/ai/stream");
        onAiStream.setParameter("streamId", "s-1");
        onAiStream.setParameter("ticket", agentTicket);
        assertRejected(onAiStream);
    }

    @Test
    void streamEndpointsRejectTheQueryTokenAndUnknownTickets() throws Exception {
        System.setProperty(HeadlessLauncher.TOKEN_PROPERTY, "desktop-token");
        // The historical ?token= fallback leaked the full credential into URL logs — it is gone.
        var withToken = new MockHttpServletRequest("GET", "/api/ai/stream");
        withToken.setParameter("streamId", "s-1");
        withToken.setParameter("token", "desktop-token");
        assertRejected(withToken);

        var withUnknownTicket = new MockHttpServletRequest("GET", "/api/ai/stream");
        withUnknownTicket.setParameter("streamId", "s-1");
        withUnknownTicket.setParameter("ticket", "forged");
        assertRejected(withUnknownTicket);

        var bare = new MockHttpServletRequest("GET", "/api/agent/stream");
        bare.setParameter("runId", "run-1");
        assertRejected(bare);
    }

    private MockHttpServletRequest requestWithTicket(String ticket) {
        var request = new MockHttpServletRequest("GET", "/api/agent/stream");
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
}
