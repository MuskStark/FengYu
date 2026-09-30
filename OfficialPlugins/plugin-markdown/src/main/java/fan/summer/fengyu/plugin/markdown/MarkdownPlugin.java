package fan.summer.fengyu.plugin.markdown;

import fan.summer.fengyu.sdk.PluginMessages;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Official Markdown editor plugin (v2, headless). Owns the server-side Markdown → HTML render via
 * commonmark (escaping raw HTML and sanitizing active URLs). The typed {@code render} RPC handler
 * in {@link MarkdownRpcHandlers} feeds the input through {@link #renderHtml(String)} and assembles
 * the generated {@code RenderOutput}; this class deliberately knows nothing about the RPC envelope.
 */
public class MarkdownPlugin {

    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer = HtmlRenderer.builder()
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build();
    private final PluginMessages msgs;

    public MarkdownPlugin(PluginMessages msgs) {
        this.msgs = msgs;
    }

    /**
     * Render Markdown source to sanitized HTML. A {@code null} input is treated as empty so the
     * generated {@code RenderInput} (whose {@code markdown} field is Gson-deserialized and may be
     * null when the caller omits it) never reaches the parser as null.
     */
    public String renderHtml(String markdown) {
        Node document = parser.parse(markdown == null ? "" : markdown);
        return renderer.render(document);
    }

    /** Localized one-line summary for a render of {@code length} input characters. */
    public String summary(int length) {
        return msgs.format("md.rendered", length);
    }
}
