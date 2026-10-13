package fan.summer.fengyu.ai.mcp.apps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MCP Apps tool-UI metadata: parsing and projection for the {@code io.modelcontextprotocol/ui}
 * extension carried in {@code tools/list} {@code _meta} and {@code ui://} resource {@code _meta}.
 *
 * <p>Wire semantics mirror the official MCP Apps specification (and its OpenAI Apps SDK
 * compatibility aliases): a tool opts into a rendered UI by naming a {@code ui://} HTML
 * resource; the resource's own {@code _meta} carries the authoritative presentation hints
 * (CSP domains, iframe permissions, size hints), with the tool-level {@code _meta.ui}
 * declaration as the fallback. Every parser is fail-soft per field — one invalid entry drops
 * that entry, never the tool.</p>
 *
 * <p>Everything here is pure computation so the catalog, the sandbox CSP header, and the SSE
 * {@code ui} projection can be unit tested without an MCP connection.</p>
 */
public final class McpToolUiMeta {

    /** MCP extension identifier (spec "Extension Identifier"; matches the official SDK). */
    public static final String UI_EXTENSION_ID = "io.modelcontextprotocol/ui";
    /** UI resource mimeType the host can render (spec MUST). */
    public static final String UI_RESOURCE_MIME_TYPE = "text/html;profile=mcp-app";
    /** Deprecated flat key {@code _meta["ui/resourceUri"]}; hosts MUST stay compatible. */
    static final String LEGACY_RESOURCE_URI_META_KEY = "ui/resourceUri";
    /** OpenAI Apps SDK alias accepted as a last-resort resourceUri source. */
    static final String OPENAI_OUTPUT_TEMPLATE_KEY = "openai/outputTemplate";

    public static final String UI_RESOURCE_SCHEME = "ui://";
    public static final int RESOURCE_URI_MAX_CHARS = 2048;
    static final int SURFACE_ID_MAX_CHARS = 128;
    static final Pattern SURFACE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]*$");

    /** HTML byte cap for a registered sandbox page (pdf.js-style single-file apps reach ~4 MiB). */
    public static final int HTML_MAX_BYTES = 16 * 1024 * 1024;
    /** Page-initiated resource reads: decoded total cap (per read). */
    public static final int UI_READ_RESOURCE_MAX_BYTES = 8 * 1024 * 1024;
    /** Persisted/SSE projection caps — oversized JSON is dropped whole, never truncated mid-structure. */
    public static final int STRUCTURED_CONTENT_MAX_BYTES = 64 * 1024;
    public static final int WIDGET_META_MAX_BYTES = 16 * 1024;
    public static final int CONTENT_MAX_BYTES = 32 * 1024;

    /** Browser permissions an {@code ui://} resource may declare (spec draft key set). */
    public static final List<String> RESOURCE_PERMISSIONS = List.of(
            "camera", "microphone", "geolocation", "clipboardWrite");

    private McpToolUiMeta() {}

    /** Client capability fragment declared at MCP initialize for the ui extension. */
    public static Map<String, Object> buildClientUiCapability() {
        return Map.of(UI_EXTENSION_ID, Map.of("mimeTypes", List.of(UI_RESOURCE_MIME_TYPE)));
    }

    // ── tool-level _meta.ui ────────────────────────────────────────────────────

    /**
     * How a tool's rendered UI may be consumed. {@code visibility} defaults to both audiences;
     * a tool without a {@code resourceUri} has no UI and never produces a descriptor.
     */
    public record McpToolUiDescriptor(
            String resourceUri,
            List<String> visibility,
            String preferredDisplayMode,
            McpUiCsp csp,
            Boolean prefersBorder,
            String surface,
            Boolean showInline) {

        public static final List<String> DEFAULT_VISIBILITY = List.of("model", "app");
    }

    /** Tool {@code _meta} → descriptor; null when the tool declares no renderable UI. */
    public static McpToolUiDescriptor parseToolUi(Object meta) {
        Map<String, Object> root = asRecord(meta);
        if (root == null) return null;
        Map<String, Object> ui = asRecord(root.get("ui"));
        String resourceUri = readResourceUri(ui == null ? null : ui.get("resourceUri"));
        if (resourceUri == null) resourceUri = readResourceUri(root.get(LEGACY_RESOURCE_URI_META_KEY));
        if (resourceUri == null) resourceUri = readResourceUri(root.get(OPENAI_OUTPUT_TEMPLATE_KEY));
        if (resourceUri == null) return null;
        List<String> visibility = readVisibility(ui == null ? null : ui.get("visibility"));
        Map<String, Object> openaiUi = readRecord(root, "openai/ui");
        String displayMode = readDisplayMode(openaiUi == null ? null : openaiUi.get("preferredModelDisplayMode"));
        Boolean prefersBorder = readBoolean(ui == null ? null : ui.get("prefersBorder"));
        if (prefersBorder == null) prefersBorder = readBoolean(root.get("openai/widgetPrefersBorder"));
        String surface = readSurfaceId(ui == null ? null : ui.get("surface"));
        Boolean showInline = readBoolean(ui == null ? null : ui.get("showInline"));
        if (showInline == null) showInline = readBoolean(root.get("openai/widgetShowCodexWidgetInline"));
        // Spec shape: the modern CSP lives at `_meta.ui.csp`; the OpenAI alias sits at the root.
        McpUiCsp csp = readCsp(readRecord(ui, "csp"), readRecord(root, "openai/widgetCSP"));
        return new McpToolUiDescriptor(resourceUri, visibility, displayMode, csp, prefersBorder, surface, showInline);
    }

    /** Independent read of {@code _meta.ui.visibility} (works with or without a resourceUri). */
    public static List<String> readToolVisibility(Object meta) {
        Map<String, Object> root = asRecord(meta);
        Map<String, Object> ui = root == null ? null : asRecord(root.get("ui"));
        return readVisibility(ui == null ? null : ui.get("visibility"));
    }

    // ── resource-level _meta ───────────────────────────────────────────────────

    /** Presentation hints carried by the selected {@code ui://} resource content item. */
    public record McpAppResourceMeta(
            McpUiCsp csp,
            List<String> permissions,
            Boolean prefersBorder,
            Integer heightHint,
            Integer minFrameHeight,
            Boolean showInline) {}

    /** Resource {@code _meta} → hints; null when the resource declares nothing we understand. */
    public static McpAppResourceMeta parseResourceMeta(Object meta) {
        Map<String, Object> root = asRecord(meta);
        if (root == null) return null;
        Map<String, Object> ui = asRecord(root.get("ui"));
        McpUiCsp csp = readCsp(readRecord(ui, "csp"), readRecord(root, "openai/widgetCSP"));
        List<String> permissions = readPermissions(ui == null ? null : ui.get("permissions"));
        Boolean prefersBorder = readBoolean(ui == null ? null : ui.get("prefersBorder"));
        if (prefersBorder == null) prefersBorder = readBoolean(root.get("openai/widgetPrefersBorder"));
        Integer heightHint = readPositiveInt(root.get("openai/widgetHeightHint"));
        Integer minFrameHeight = readPositiveInt(root.get("openai/widgetMinFrameHeight"));
        Boolean showInline = readBoolean(ui == null ? null : ui.get("showInline"));
        if (showInline == null) showInline = readBoolean(root.get("openai/widgetShowCodexWidgetInline"));
        if (csp == null && permissions.isEmpty() && prefersBorder == null
                && heightHint == null && minFrameHeight == null && showInline == null) {
            return null;
        }
        return new McpAppResourceMeta(csp, permissions, prefersBorder, heightHint, minFrameHeight, showInline);
    }

    // ── CSP domains ────────────────────────────────────────────────────────────

    /**
     * The four CSP domain allowlists an {@code ui://} resource (or its tool) may declare.
     * Anything not declared stays fully blocked — the sandbox default is {@code default-src 'none'}.
     */
    public record McpUiCsp(List<String> connectDomains, List<String> resourceDomains,
                           List<String> frameDomains, List<String> baseUriDomains) {}

    /**
     * Normalizes one declared origin to a CSP host-source. {@code https:}/{@code wss:} accept any
     * host; plain {@code http:}/{@code ws:} only the exact loopback address {@code 127.0.0.1} (a
     * plugin's own local service bridge — never {@code localhost}, which hosts files can rewrite,
     * and never other addresses). Credentials, query, hash and paths are rejected. Returns null
     * for anything else, so a malformed entry degrades to "domain not allowed".
     */
    public static String normalizeCspOrigin(Object value) {
        if (!(value instanceof String raw) || raw.isBlank()) return null;
        java.net.URI uri;
        try {
            uri = java.net.URI.create(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        String host = uri.getHost();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        boolean loopback = "127.0.0.1".equals(host) && ("http".equals(scheme) || "ws".equals(scheme));
        if (!scheme.equals("https") && !scheme.equals("wss") && !loopback) return null;
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) return null;
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) return null;
        if (uri.getPort() == -1) return scheme + "://" + host;
        return scheme + "://" + host + ":" + uri.getPort();
    }

    private static List<String> readOriginList(Object value) {
        if (!(value instanceof List<?> list)) return null;
        Set<String> origins = new LinkedHashSet<>();
        for (Object item : list) {
            String origin = normalizeCspOrigin(item);
            if (origin != null) origins.add(origin);
        }
        return origins.isEmpty() ? null : List.copyOf(origins);
    }

    /** Merges resource-level and tool-level CSP: resource wins per whole record, never per domain. */
    public static McpUiCsp effectiveCsp(McpUiCsp resourceLevel, McpUiCsp toolLevel) {
        if (resourceLevel != null) return resourceLevel;
        return toolLevel;
    }

    // ── sandbox CSP header ─────────────────────────────────────────────────────

    /**
     * Builds the Content-Security-Policy header served with a sandbox app page. Baseline is the
     * spec block ({@code default-src 'none'}); declared domains are the ONLY relaxations, with
     * two pragmatic additions: inline scripts/styles must pass (MCP Apps HTML is inlined by
     * design — the isolation boundary is the sandboxed opaque-origin iframe, not inline bans),
     * and {@code blob:}/{@code data:} script sources pass so single-file bundlers' workers work.
     * {@code frameAncestors} pins framing to the FengYu host origins.
     */
    public static String buildAppPageCsp(McpUiCsp csp, String frameAncestors) {
        List<String> resource = csp == null || csp.resourceDomains() == null
                ? List.of() : csp.resourceDomains();
        List<String> connect = csp == null || csp.connectDomains() == null
                ? List.of() : csp.connectDomains();
        List<String> frame = csp == null || csp.frameDomains() == null
                ? List.of() : csp.frameDomains();
        List<String> baseUri = csp == null || csp.baseUriDomains() == null
                ? List.of() : csp.baseUriDomains();
        List<String> script = new ArrayList<>(List.of("'self'", "'unsafe-inline'", "blob:", "data:"));
        script.addAll(resource);
        StringBuilder out = new StringBuilder();
        out.append("default-src 'none'");
        out.append("; script-src ").append(String.join(" ", script));
        out.append("; style-src 'self' 'unsafe-inline'").append(joinDomains(resource));
        out.append("; img-src 'self' data: blob:").append(joinDomains(resource));
        out.append("; media-src 'self' data: blob:").append(joinDomains(resource));
        out.append("; font-src 'self' data:").append(joinDomains(resource));
        out.append("; connect-src ").append(connect.isEmpty() ? "'none'" : String.join(" ", connect));
        out.append("; frame-src ").append(frame.isEmpty() ? "'none'" : String.join(" ", frame));
        out.append("; base-uri 'self'").append(joinDomains(baseUri));
        out.append("; worker-src 'self' blob:");
        out.append("; object-src 'none'");
        out.append("; form-action 'none'");
        out.append("; frame-ancestors ").append(frameAncestors == null || frameAncestors.isBlank()
                ? "'self'" : frameAncestors);
        return out.toString();
    }

    /** Origins allowed to frame a sandbox app page: the FengYu host (web, dev, desktop shell). */
    public static final String HOST_FRAME_ANCESTORS = "'http://127.0.0.1:*' 'http://localhost:*'";

    private static String joinDomains(List<String> domains) {
        return domains.isEmpty() ? "" : " " + String.join(" ", domains);
    }

    // ── ui projection for the SSE tool event ───────────────────────────────────

    /**
     * Builds the additive {@code ui} payload attached to a tool-result SSE event. JSON fields are
     * byte-bounded: an oversized field is omitted whole and counted in {@code truncatedBytes} so
     * the card can say "structured result truncated" instead of rendering half a structure.
     */
    public static Map<String, Object> projectUiPayload(
            UiToolInfo info, CapturedResult captured) {
        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("serverId", info.serverId());
        ui.put("serverName", info.serverName());
        ui.put("tool", info.toolName());
        ui.put("resourceUri", info.descriptor().resourceUri());
        McpToolUiDescriptor d = info.descriptor();
        if (d.preferredDisplayMode() != null) ui.put("preferredDisplayMode", d.preferredDisplayMode());
        if (d.prefersBorder() != null) ui.put("prefersBorder", d.prefersBorder());
        if (d.surface() != null) ui.put("surface", d.surface());
        if (d.showInline() != null) ui.put("showInline", d.showInline());
        if (captured == null) return ui;
        if (captured.isError()) ui.put("isError", true);
        long truncatedBytes = 0;
        Bounded structured = boundJson(captured.structuredContent(), STRUCTURED_CONTENT_MAX_BYTES);
        Bounded meta = boundJson(captured.meta(), WIDGET_META_MAX_BYTES);
        Bounded content = boundJson(captured.content(), CONTENT_MAX_BYTES);
        if (structured != null && !structured.truncated()) ui.put("structuredContent", structured.text());
        if (meta != null && !meta.truncated()) ui.put("widgetMeta", meta.text());
        if (content != null && !content.truncated()) ui.put("content", content.text());
        if (structured != null && structured.truncated()) truncatedBytes += structured.bytes();
        if (meta != null && meta.truncated()) truncatedBytes += meta.bytes();
        if (content != null && content.truncated()) truncatedBytes += content.bytes();
        if (truncatedBytes > 0) {
            ui.put("truncated", true);
            ui.put("truncatedBytes", truncatedBytes);
        }
        return ui;
    }

    /** Catalog entry for one UI-capable wire tool. */
    public record UiToolInfo(String serverId, String serverName, String toolName, String wireName,
                             McpToolUiDescriptor descriptor) {}

    /** Raw MCP result captured at callback execution for a UI tool call. */
    public record CapturedResult(Boolean isError, Object structuredContent,
                                 Map<String, Object> meta, List<Object> content) {}

    private record Bounded(String text, boolean truncated, long bytes) {}

    private static Bounded boundJson(Object value, int maxBytes) {
        if (value == null) return null;
        String text;
        try {
            text = JSON.writeValueAsString(value);
        } catch (Exception e) {
            return null; // unserializable projection: omit the field, never break the event
        }
        if (text == null) return null;
        long bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes <= maxBytes) return new Bounded(text, false, bytes);
        return new Bounded(null, true, bytes);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    // ── scalar readers (fail-soft per field) ───────────────────────────────────

    private static String readResourceUri(Object value) {
        if (!(value instanceof String raw)) return null;
        String trimmed = raw.trim();
        if (!trimmed.startsWith(UI_RESOURCE_SCHEME)) return null;
        if (trimmed.length() > RESOURCE_URI_MAX_CHARS) return null;
        return trimmed;
    }

    private static List<String> readVisibility(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return McpToolUiDescriptor.DEFAULT_VISIBILITY;
        }
        Set<String> out = new LinkedHashSet<>();
        for (Object item : list) {
            if ("model".equals(item) || "app".equals(item)) out.add((String) item);
        }
        return out.isEmpty() ? McpToolUiDescriptor.DEFAULT_VISIBILITY : List.copyOf(out);
    }

    private static String readDisplayMode(Object value) {
        if ("inline".equals(value) || "fullscreen".equals(value) || "pip".equals(value)) {
            return (String) value;
        }
        return null;
    }

    private static McpUiCsp readCsp(Map<String, Object> ui, Map<String, Object> openaiWidgetCsp) {
        List<String> connect = firstDomain(ui, openaiWidgetCsp, "connectDomains", "connect_domains");
        List<String> resource = firstDomain(ui, openaiWidgetCsp, "resourceDomains", "resource_domains");
        List<String> frame = firstDomain(ui, openaiWidgetCsp, "frameDomains", "frame_domains");
        List<String> baseUri = firstDomain(ui, openaiWidgetCsp, "baseUriDomains", "base_uri_domains");
        if (connect == null && resource == null && frame == null && baseUri == null) return null;
        return new McpUiCsp(connect, resource, frame, baseUri);
    }

    private static List<String> firstDomain(Map<String, Object> modern, Map<String, Object> legacy,
                                            String camel, String snake) {
        List<String> hit = readOriginList(modern == null ? null : modern.get(camel));
        if (hit != null) return hit;
        hit = readOriginList(legacy == null ? null : legacy.get(camel));
        if (hit != null) return hit;
        return readOriginList(legacy == null ? null : legacy.get(snake));
    }

    private static List<String> readPermissions(Object value) {
        Map<String, Object> root = asRecord(value);
        if (root == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String key : RESOURCE_PERMISSIONS) {
            if (asRecord(root.get(key)) != null) out.add(key);
        }
        return List.copyOf(out);
    }

    private static String readSurfaceId(Object value) {
        if (!(value instanceof String raw)) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > SURFACE_ID_MAX_CHARS) return null;
        return SURFACE_ID_PATTERN.matcher(trimmed).matches() ? trimmed : null;
    }

    private static Integer readPositiveInt(Object value) {
        if (value instanceof Number n && n.longValue() > 0 && n.longValue() <= Integer.MAX_VALUE) {
            return (int) n.longValue();
        }
        return null;
    }

    private static Boolean readBoolean(Object value) {
        return value instanceof Boolean b ? b : null;
    }

    private static Map<String, Object> readRecord(Map<String, Object> root, String key) {
        if (root == null) return null;
        return asRecord(root.get(key));
    }

    private static Map<String, Object> asRecord(Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key) out.put(key, entry.getValue());
        }
        return out;
    }
}
