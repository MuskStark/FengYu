package fan.summer.fengyu.web.filter;

import fan.summer.fengyu.HeadlessLauncher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenAuthFilterTest {

    private final TokenAuthFilter filter = new TokenAuthFilter();

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
}
