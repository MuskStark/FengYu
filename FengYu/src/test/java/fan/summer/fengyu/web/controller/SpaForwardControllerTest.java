package fan.summer.fengyu.web.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the SPA forward controller maps the known Vue routes to index.html while leaving API,
 * static asset, and plugin-runtime routes to their own handlers.
 */
class SpaForwardControllerTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SpaForwardController()).build();

    @Test
    void forwardsStaticSpaRoutes() throws Exception {
        for (String path : List.of("/", "/setup", "/tools", "/agent", "/flows", "/flows/new",
                "/flows/workflow-123", "/plugins", "/account", "/settings", "/about",
                "/plugin/fan.summer.excel")) {
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        }
    }

    @Test
    void doesNotClaimApiOrAssetRoutes() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isNotFound());
        mvc.perform(get("/assets/app.js")).andExpect(status().isNotFound());
        mvc.perform(get("/plugin-runtime/fan.summer.excel/ui/index.html")).andExpect(status().isNotFound());
    }
}
