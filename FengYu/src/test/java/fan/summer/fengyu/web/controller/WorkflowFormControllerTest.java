package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.workflow.WorkflowWebhookAuthenticationException;
import fan.summer.fengyu.ai.workflow.WorkflowWebhookTriggerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowFormControllerTest {

    private WorkflowWebhookTriggerService triggers;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        triggers = mock(WorkflowWebhookTriggerService.class);
        mvc = MockMvcBuilders.standaloneSetup(new WorkflowFormController(triggers)).build();
    }

    @Test
    void pageRendersSchemaFieldsWithEscaping() throws Exception {
        when(triggers.formView("form-1")).thenReturn(Map.of(
                "name", "Weekly <Digest>",
                "description", "Summarize & send",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "topic", Map.of("type", "string", "title", "Topic"),
                                "count", Map.of("type", "integer", "title", "Count", "default", 3),
                                "dryRun", Map.of("type", "boolean", "title", "Dry run"),
                                "level", Map.of("type", "string", "title", "Level",
                                        "enum", List.of("low", "high")),
                                "notes", Map.of("type", "string", "title", "Notes",
                                        "x-fengyu-multiline", true)),
                        "required", List.of("topic"))));

        String page = mvc.perform(get("/api/workflow-forms/form-1").locale(Locale.ENGLISH))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'"))
                .andReturn().getResponse().getContentAsString();

        assertTrue(page.contains("Weekly &lt;Digest&gt;"), "flow name must be HTML-escaped");
        assertTrue(page.contains("Summarize &amp; send"));
        assertTrue(page.contains("name=\"topic\" required"));
        assertTrue(page.contains("type=\"number\""));
        assertTrue(page.contains("type=\"checkbox\""));
        assertTrue(page.contains("<select name=\"level\">"));
        assertTrue(page.contains("<textarea name=\"notes\""));
        assertTrue(page.contains("action=\"/api/workflow-forms/form-1/submit\""));
    }

    @Test
    void unknownFormIdIsAnHtml404() throws Exception {
        when(triggers.formView("ghost")).thenThrow(new WorkflowWebhookAuthenticationException());
        mvc.perform(get("/api/workflow-forms/ghost").locale(Locale.ENGLISH))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    @Test
    void submitCoercesAndDelivers() throws Exception {
        when(triggers.formView("form-1")).thenReturn(Map.of(
                "name", "Digest", "description", "",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "topic", Map.of("type", "string"),
                                "count", Map.of("type", "integer"),
                                "dryRun", Map.of("type", "boolean"),
                                "rules", Map.of("type", "array")),
                        "required", List.of("topic"))));
        when(triggers.deliverForm(eq("form-1"), anyMap())).thenReturn(
                new WorkflowWebhookTriggerService.DeliveryResult("form-1", "task-9", true, false, "QUEUED", null));

        mvc.perform(post("/api/workflow-forms/form-1/submit")
                        .locale(Locale.ENGLISH)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("topic=AI%20news&count=3&dryRun=on&rules=%5B%22a%22%5D&ignored=x"))
                .andExpect(status().isOk())
                .andExpect(result -> assertTrue(result.getResponse().getContentAsString().contains("task-9")));
    }

    @Test
    void submitWithMalformedJsonRendersAnHtmlError() throws Exception {
        when(triggers.formView("form-1")).thenReturn(Map.of(
                "name", "Digest", "description", "",
                "inputSchema", Map.of("type", "object",
                        "properties", Map.of("rules", Map.of("type", "array")),
                        "required", List.of())));

        mvc.perform(post("/api/workflow-forms/form-1/submit")
                        .locale(Locale.ENGLISH)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("rules=not-json"))
                .andExpect(status().isOk())
                .andExpect(result -> assertTrue(result.getResponse().getContentAsString()
                        .contains("invalid format")));
    }

    @Test
    void emptyOptionalFieldsAreOmitted() {
        Map<String, Object> inputs = WorkflowFormController.coerceForm(
                "topic=&count=2".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Map.of("topic", Map.of("type", "string"), "count", Map.of("type", "integer")),
                Locale.ENGLISH);
        org.junit.jupiter.api.Assertions.assertEquals(Map.of("count", 2L), inputs);
    }
}
