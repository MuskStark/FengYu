package fan.summer.fengyu.plugin.markdown;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Official Markdown editor plugin (v2, headless). Phase 1 walking-skeleton carrier — it exercises
 * both Phase-1 paths: a non-trivial micro-frontend UI (split editor + live preview) and the
 * backend {@code invoke} path (server-side Markdown → HTML render via commonmark).
 *
 * <p>Replaces the old JavaFX {@code MarkdownEditorPlugin}'s regex {@code mdToHtml} with the
 * commonmark library for correctness. Rendering moves server-side; the frontend debounces
 * {@code render} calls on each edit.
 */
public class MarkdownPlugin {

    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer = HtmlRenderer.builder()
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build();

    /**
     * Actions:
     * <ul>
     *   <li>{@code "render"} with {@code {markdown: String}} →
     *       {@code {success, summary, html}} (tool-return JSON contract).</li>
     * </ul>
     */
    public Object invoke(String action, Map<String, Object> args) {
        if (!"render".equals(action)) {
            throw new IllegalArgumentException("Unknown action: " + action);
        }
        Object md = args == null ? null : args.get("markdown");
        String markdown = md == null ? "" : md.toString();

        Node document = parser.parse(markdown);
        String html = renderer.render(document);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("summary", "rendered " + markdown.length() + " chars");
        out.put("html", html);
        return out;
    }
}
