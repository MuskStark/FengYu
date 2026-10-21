package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.workflow.WorkflowWebhookAuthenticationException;
import fan.summer.fengyu.ai.workflow.WorkflowWebhookTriggerService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loopback form pages for published workflows: the run-input schema rendered as a plain
 * HTML form, submitting as a background-task run of the flow.
 *
 * <p>Security model (mirrors the webhook surface — see {@code TokenAuthFilter}): the page and
 * its submit endpoint are token-exempt because a browser navigation / browser-native form POST
 * cannot attach headers; the unguessable trigger id (a random UUID) in the URL is the per-form
 * capability, the DNS-rebinding loopback-Host gate applies before every exemption, and the
 * server binds loopback only. Form triggers are created through the token-authenticated
 * webhook-trigger API, which already rejects ephemeral file inputs; submissions share the
 * webhook delivery path's isolation screen, schema validation, audit trail, and idempotent
 * background-task admission. The page carries no script: a plain form POST keeps the CSP at
 * {@code default-src 'none'}.</p>
 */
@RestController
public class WorkflowFormController {

    static final int MAX_PAYLOAD_BYTES = 256 * 1024;

    private final WorkflowWebhookTriggerService triggers;

    public WorkflowFormController(WorkflowWebhookTriggerService triggers) {
        this.triggers = triggers;
    }

    @GetMapping(value = "/api/workflow-forms/{triggerId}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@PathVariable String triggerId,
            @RequestHeader(name = "Accept-Language", required = false) String acceptLanguage) {
        Locale locale = formLocale(acceptLanguage);
        Map<String, Object> view;
        try {
            view = triggers.formView(triggerId);
        } catch (WorkflowWebhookAuthenticationException unknown) {
            return ResponseEntity.status(404).contentType(MediaType.TEXT_HTML)
                    .body(messagePage(false, text(locale, "未找到表单", "Form not found"), locale));
        }
        return html(formPage(triggerId, view, locale));
    }

    @PostMapping(value = "/api/workflow-forms/{triggerId}/submit",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> submit(@PathVariable String triggerId,
            jakarta.servlet.http.HttpServletRequest request,
            @RequestHeader(name = "Accept-Language", required = false) String acceptLanguage)
            throws java.io.IOException {
        Locale locale = formLocale(acceptLanguage);
        Map<String, Object> view;
        try {
            view = triggers.formView(triggerId);
        } catch (WorkflowWebhookAuthenticationException unknown) {
            return ResponseEntity.status(404).contentType(MediaType.TEXT_HTML)
                    .body(messagePage(false, text(locale, "未找到表单", "Form not found"), locale));
        }
        Map<String, Object> inputs;
        WorkflowWebhookTriggerService.DeliveryResult result;
        try {
            inputs = coerceForm(readPayloadBounded(request),
                    schemaProperties(view.get("inputSchema")), locale);
            result = triggers.deliverForm(triggerId, inputs);
        } catch (IllegalArgumentException badInput) {
            return html(messagePage(false, badInput.getMessage(), locale));
        } catch (WorkflowWebhookAuthenticationException unknown) {
            return ResponseEntity.status(404).contentType(MediaType.TEXT_HTML)
                    .body(messagePage(false, text(locale, "未找到表单", "Form not found"), locale));
        }
        String headline = result.accepted()
                ? text(locale, "已提交，流程正在后台运行", "Submitted — the flow is running in the background")
                : text(locale, "提交未被接受", "The submission was not accepted");
        StringBuilder detail = new StringBuilder(headline);
        if (result.taskId() != null) detail.append("<small>").append(escape(result.taskId())).append("</small>");
        if (result.error() != null) detail.append("<small>").append(escape(result.error())).append("</small>");
        return html(messagePage(result.accepted(), detail.toString(), locale));
    }

    // ── rendering ────────────────────────────────────────────────────────

    private String formPage(String triggerId, Map<String, Object> view, Locale locale) {
        String name = String.valueOf(view.getOrDefault("name", ""));
        String description = String.valueOf(view.getOrDefault("description", ""));
        Map<String, Object> properties = schemaProperties(view.get("inputSchema"));
        List<?> required = view.get("inputSchema") instanceof Map<?, ?> schema
                && schema.get("required") instanceof List<?> list ? list : List.of();

        StringBuilder body = new StringBuilder();
        body.append("<h1>").append(escape(name)).append("</h1>");
        if (!description.isBlank()) body.append("<p class=\"desc\">").append(escape(description)).append("</p>");
        body.append("<form method=\"post\" action=\"")
                .append(escape("/api/workflow-forms/" + triggerId + "/submit")).append("\">");
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> raw)) continue;
            Map<?, ?> property = raw;
            String field = entry.getKey();
            boolean need = required.contains(field);
            Object titleValue = property.get("title");
            String title = titleValue == null ? field : String.valueOf(titleValue);
            body.append("<label class=\"field\">")
                    .append("<span>").append(escape(title)).append(need ? " <em>*</em>" : "").append("</span>");
            body.append(fieldInput(field, property, need));
            if (property.get("description") instanceof String hint && !hint.isBlank()) {
                body.append("<small>").append(escape(hint)).append("</small>");
            }
            body.append("</label>");
        }
        body.append("<button type=\"submit\">")
                .append(escape(text(locale, "提交并运行", "Submit and run"))).append("</button>");
        body.append("</form>");
        return shell(name, body.toString(), locale);
    }

    private String fieldInput(String field, Map<?, ?> property, boolean need) {
        Object typeValue = property.get("type");
        String type = typeValue == null ? "string" : String.valueOf(typeValue);
        String name = "name=\"" + escape(field) + "\"";
        String required = need ? " required" : "";
        Object def = property.get("default");
        if (property.get("enum") instanceof List<?> options && !options.isEmpty()) {
            StringBuilder select = new StringBuilder("<select ").append(name).append(required).append(">");
            if (!need) select.append("<option value=\"\"></option>");
            for (Object option : options) {
                String value = String.valueOf(option);
                select.append("<option value=\"").append(escape(value)).append("\"");
                if (def != null && value.equals(String.valueOf(def))) select.append(" selected");
                select.append(">").append(escape(value)).append("</option>");
            }
            return select.append("</select>").toString();
        }
        if ("boolean".equals(type)) {
            return "<input type=\"checkbox\" " + name + " value=\"on\"" + (Boolean.TRUE.equals(def) ? " checked" : "") + ">";
        }
        if ("integer".equals(type) || "number".equals(type)) {
            return "<input type=\"number\" step=\"any\" " + name + required
                    + (def instanceof Number number ? " value=\"" + number + "\"" : "") + ">";
        }
        if ("array".equals(type) || "object".equals(type)) {
            return "<textarea " + name + " rows=\"4\" placeholder=\"JSON\"" + required + ">"
                    + (def == null ? "" : escape(writeJson(def))) + "</textarea>";
        }
        if (Boolean.TRUE.equals(property.get("x-fengyu-multiline"))) {
            return "<textarea " + name + " rows=\"4\"" + required + ">"
                    + (def == null ? "" : escape(String.valueOf(def))) + "</textarea>";
        }
        return "<input type=\"text\" " + name + required
                + (def == null ? "" : " value=\"" + escape(String.valueOf(def)) + "\"") + ">";
    }

    /** Result / error page with a way back to the form. */
    private String messagePage(boolean ok, String htmlDetail, Locale locale) {
        String back = " <a href=\".\">" + escape(text(locale, "返回表单", "Back to the form")) + "</a>";
        String body = "<div class=\"" + (ok ? "ok" : "err") + "\">" + htmlDetail + back + "</div>";
        return shell(text(locale, "流程表单", "Flow form"), body, locale);
    }

    private String shell(String title, String bodyHtml, Locale locale) {
        return "<!doctype html><html lang=\"" + (isChinese(locale) ? "zh" : "en") + "\"><head>"
                + "<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + escape(title) + "</title>"
                + "<style>" + STYLE + "</style></head><body><main>" + bodyHtml + "</main></body></html>";
    }

    private static final String STYLE = """
            :root{color-scheme:light dark}
            body{margin:0;font:14px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif}
            main{max-width:520px;margin:8vh auto;padding:0 16px}
            h1{font-size:20px;margin:0 0 6px}
            .desc{margin:0 0 18px;opacity:.7}
            .field{display:flex;flex-direction:column;gap:4px;margin:0 0 14px}
            .field>span{font-weight:600}
            .field small{opacity:.65}
            em{color:#c0392b;font-style:normal}
            input,select,textarea{font:inherit;padding:7px 9px;border:1px solid rgba(125,125,125,.45);border-radius:8px;background:transparent;color:inherit}
            input[type=checkbox]{width:18px;height:18px}
            textarea{resize:vertical;font-family:ui-monospace,monospace}
            button{font:inherit;font-weight:600;padding:9px 18px;border:0;border-radius:9px;background:#6c53b8;color:#fff;cursor:pointer}
            .ok,.err{padding:14px 16px;border-radius:10px;display:flex;flex-direction:column;gap:4px}
            .ok{background:rgba(46,125,50,.14)} .err{background:rgba(198,40,40,.12)}
            .ok small,.err small{opacity:.7;word-break:break-all}
            a{color:#6c53b8}
            """;

    private static ResponseEntity<String> html(String page) {
        return ResponseEntity.ok()
                .header("Content-Security-Policy",
                        "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'")
                .contentType(MediaType.TEXT_HTML)
                .body(page);
    }

    // ── form parsing / coercion ──────────────────────────────────────────

    /** Decodes the urlencoded body and coerces each value against the schema's declared type. */
    static Map<String, Object> coerceForm(byte[] payload, Map<String, Object> properties, Locale locale) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        String raw = new String(payload, StandardCharsets.UTF_8);
        if (raw.isBlank()) return inputs;
        for (String pair : raw.split("&")) {
            int split = pair.indexOf('=');
            String key = urlDecode(split < 0 ? pair : pair.substring(0, split));
            String value = split < 0 ? "" : urlDecode(pair.substring(split + 1));
            if (key.isBlank() || inputs.containsKey(key)) continue;
            Object coerced = coerceValue(key, value, properties.get(key), locale);
            if (coerced != null) inputs.put(key, coerced);
        }
        return inputs;
    }

    /** null = treat as unset (empty optional field, unchecked checkbox). */
    private static Object coerceValue(String key, String value, Object property, Locale locale) {
        Object typeValue = property instanceof Map<?, ?> map ? map.get("type") : null;
        String type = typeValue == null ? "string" : String.valueOf(typeValue);
        if (value.isBlank()) return null;
        try {
            return switch (type) {
                case "integer" -> Long.parseLong(value.trim());
                case "number" -> Double.parseDouble(value.trim());
                case "boolean" -> "on".equalsIgnoreCase(value.trim()) || "true".equalsIgnoreCase(value.trim());
                case "array", "object" -> JSON.readValue(value, Object.class);
                default -> value;
            };
        } catch (Exception malformed) {
            throw new IllegalArgumentException(text(locale,
                    "字段「" + key + "」的格式不正确",
                    "Field '" + key + "' has an invalid format"), malformed);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemaProperties(Object inputSchema) {
        if (inputSchema instanceof Map<?, ?> schema && schema.get("properties") instanceof Map<?, ?> raw) {
            return (Map<String, Object>) raw;
        }
        return Map.of();
    }

    /** The real byte cap on the streamed body — a forged or absent Content-Length cannot bypass it. */
    private static byte[] readPayloadBounded(jakarta.servlet.http.HttpServletRequest request)
            throws java.io.IOException {
        String declared = request.getHeader("Content-Length");
        if (declared != null) {
            try {
                if (Long.parseLong(declared.trim()) > MAX_PAYLOAD_BYTES) {
                    throw new IllegalArgumentException("Form payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
                }
            } catch (NumberFormatException ignored) {
                // the streamed count below is the real guard
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        try (InputStream in = request.getInputStream()) {
            int count;
            while ((count = in.read(buffer)) >= 0) {
                total += count;
                if (total > MAX_PAYLOAD_BYTES) {
                    throw new IllegalArgumentException("Form payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
                }
                out.write(buffer, 0, count);
            }
        }
        return out.toByteArray();
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception impossible) {
            return String.valueOf(value);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            com.fasterxml.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();

    /** The raw-header resolveLocale idiom (AiController pattern) — Locale injection is unreliable here. */
    private static Locale formLocale(String acceptLanguage) {
        return fan.summer.fengyu.plugin.market.ManifestI18n
                .resolveLocale(acceptLanguage).equalsIgnoreCase("zh") ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
    }

    private static boolean isChinese(Locale locale) {
        return locale != null && locale.getLanguage().equalsIgnoreCase("zh");
    }

    private static String text(Locale locale, String zh, String en) {
        return isChinese(locale) ? zh : en;
    }

    private static String escape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            switch (value.charAt(i)) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(value.charAt(i));
            }
        }
        return out.toString();
    }
}
