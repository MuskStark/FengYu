package fan.summer.fengyu.web.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.config.annotation.DelegatingWebMvcConfiguration;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the CORS method allow-list in {@link WebConfig} against the desktop webview's
 * cross-origin calls, through real MVC infrastructure (exactly what @EnableWebMvc wires
 * in production). The shell serves the SPA from the {@code app://shell} custom protocol,
 * so EVERY non-safelisted method (PATCH included — used by the plugin-store enable toggle
 * and the skill enabled toggle) triggers a preflight that must pass; the audit found
 * PATCH missing from {@code allowedMethods}.
 *
 * <p>The context is built PROGRAMMATICALLY on purpose: a @Configuration/@EnableWebMvc
 * probe class on the test classpath gets component-scanned into every OTHER Spring test
 * that scans {@code fan.summer.fengyu} (repository slices included), dragging full MVC
 * infrastructure into contexts with no servlet environment. The probe below is a plain
 * @RestController — no more polluting than the application's own controllers, which those
 * slices already exclude — and the MVC infrastructure is registered per-test, never
 * discovered by scanning.
 */
class WebConfigCorsTest {

    private GenericWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        context = new GenericWebApplicationContext();
        // WebConfig's standard-named localeResolver overrides the MVC default, mirroring
        // how Boot's auto-configuration backs off in the production application context.
        context.setAllowBeanDefinitionOverriding(true);
        context.setServletContext(new MockServletContext());
        AnnotatedBeanDefinitionReader reader = new AnnotatedBeanDefinitionReader(context);
        reader.register(WebConfig.class, DelegatingWebMvcConfiguration.class, ProbeController.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() {
        if (context != null) context.close();
    }

    @Test
    void patchPreflightFromDesktopShellOriginIsAllowed() throws Exception {
        mvc.perform(options("/api/cors-probe")
                        .header(HttpHeaders.ORIGIN, "app://shell")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("PATCH")));
    }

    @Test
    void patchPreflightFromViteDevOriginIsAllowed() throws Exception {
        mvc.perform(options("/api/cors-probe")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("PATCH")));
    }

    @Test
    void preflightFromUnknownOriginIsRejected() throws Exception {
        mvc.perform(options("/api/cors-probe")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH"))
                .andExpect(status().isForbidden());
    }

    /** A PATCH endpoint so the CORS preflight has a concrete mapping to match. */
    @RestController
    static class ProbeController {
        @PatchMapping("/api/cors-probe")
        String probe() {
            return "ok";
        }
    }
}
