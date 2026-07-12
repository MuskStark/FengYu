package fan.summer.fengyu.ai.util;

import fan.summer.fengyu.api.theme.ThemeService;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Renders Markdown text to styled HTML for display in a JavaFX {@code WebView}.
 *
 * <p>Uses the <a href="https://commonmark.us/">CommonMark</a> library for parsing
 * and produces self-contained HTML wrapped in a {@code <html>} document with an
 * embedded dark-theme stylesheet. The renderer is safe to use with any WebView
 * in the application.</p>
 *
 * <p>The embedded CSS is theme-aware: a dark palette ({@code #1e1e2e}
 * background) or a light palette ({@code #ffffff} background), picked by
 * {@link fan.summer.fengyu.api.theme.ThemeService}. Both variants use a monospaced
 * font for code blocks and inline code, and support the full CommonMark
 * spec including tables, blockquotes, and images.</p>
 *
 * <p>Two rendering modes are provided:</p>
 * <ul>
 *   <li>{@link #render(String)} — full CommonMark parsing (headers, lists, code
 *       blocks, links, images, etc.)</li>
 *   <li>{@link #renderPlain(String)} — HTML-escapes the input and converts newlines
 *       to {@code <br>} tags without parsing Markdown syntax.</li>
 * </ul>
 *
 * @see org.commonmark.parser.Parser
 * @see org.commonmark.renderer.html.HtmlRenderer
 */
public final class MarkdownRenderer {

    private static final Parser PARSER = Parser.builder().build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().build();

    private static final String DARK_CSS = """
        body {
            margin: 0; padding: 10px 14px;
            font-family: -apple-system, 'SF Pro Text', 'Helvetica Neue', sans-serif;
            font-size: 13.5px; line-height: 1.55;
            color: rgba(255,255,255,0.98);
            background: #1e1e2e;
            border-radius: 14px;
            overflow-wrap: break-word; word-wrap: break-word;
        }
        pre {
            background: rgba(255,255,255,0.06);
            border: 1px solid rgba(255,255,255,0.10);
            border-radius: 8px;
            padding: 10px 12px;
            overflow-x: auto;
            font-size: 12.5px;
        }
        code {
            font-family: 'SF Mono','Consolas','Menlo',monospace;
            font-size: 12.5px;
        }
        p code {
            background: rgba(255,255,255,0.08);
            padding: 1px 5px; border-radius: 4px;
        }
        a { color: #3574F0; }
        blockquote {
            border-left: 3px solid rgba(255,255,255,0.20);
            margin: 6px 0; padding: 2px 12px;
            color: rgba(255,255,255,0.65);
        }
        table { border-collapse: collapse; margin: 8px 0; }
        th, td {
            border: 1px solid rgba(255,255,255,0.12);
            padding: 5px 10px; text-align: left;
        }
        th { background: rgba(255,255,255,0.06); }
        hr { border: none; border-top: 1px solid rgba(255,255,255,0.10); margin: 10px 0; }
        img { max-width: 100%; border-radius: 8px; }
        ul, ol { padding-left: 20px; }
        li { margin: 2px 0; }
        h1 { font-size: 18px; margin: 8px 0 4px; }
        h2 { font-size: 16px; margin: 8px 0 4px; }
        h3 { font-size: 14.5px; margin: 6px 0 3px; }
        details.sk-collapse {
            background: rgba(255,255,255,0.03);
            border: 1px solid rgba(255,255,255,0.06);
            border-radius: 10px;
            padding: 6px 12px;
            margin: 4px 0;
        }
        details.sk-collapse > summary {
            cursor: pointer;
            color: rgba(255,255,255,0.50);
            font-size: 12px;
            font-weight: 600;
            list-style: none;
        }
        details.sk-collapse[open] > summary { margin-bottom: 6px; }
        """;

    private static final String LIGHT_CSS = """
        body {
            margin: 0; padding: 10px 14px;
            font-family: -apple-system, 'SF Pro Text', 'Helvetica Neue', sans-serif;
            font-size: 13.5px; line-height: 1.55;
            color: #1E1E1E;
            background: #ffffff;
            border-radius: 14px;
            overflow-wrap: break-word; word-wrap: break-word;
        }
        pre {
            background: #F7F8FA;
            border: 1px solid #DADCE0;
            border-radius: 8px;
            padding: 10px 12px;
            overflow-x: auto;
            font-size: 12.5px;
        }
        code {
            font-family: 'SF Mono','Consolas','Menlo',monospace;
            font-size: 12.5px;
        }
        p code {
            background: #EBECEF;
            padding: 1px 5px; border-radius: 4px;
        }
        a { color: #3574F0; }
        blockquote {
            border-left: 3px solid #C9CDD3;
            margin: 6px 0; padding: 2px 12px;
            color: #5A5D60;
        }
        table { border-collapse: collapse; margin: 8px 0; }
        th, td {
            border: 1px solid #DADCE0;
            padding: 5px 10px; text-align: left;
        }
        th { background: #F7F8FA; }
        hr { border: none; border-top: 1px solid #DADCE0; margin: 10px 0; }
        img { max-width: 100%; border-radius: 8px; }
        ul, ol { padding-left: 20px; }
        li { margin: 2px 0; }
        h1 { font-size: 18px; margin: 8px 0 4px; }
        h2 { font-size: 16px; margin: 8px 0 4px; }
        h3 { font-size: 14.5px; margin: 6px 0 3px; }
        details.sk-collapse {
            background: #F7F8FA;
            border: 1px solid #DADCE0;
            border-radius: 10px;
            padding: 6px 12px;
            margin: 4px 0;
        }
        details.sk-collapse > summary {
            cursor: pointer;
            color: #5A5D60;
            font-size: 12px;
            font-weight: 600;
            list-style: none;
        }
        details.sk-collapse[open] > summary { margin-bottom: 6px; }
        """;

    private MarkdownRenderer() {}

    /**
     * Parses the given Markdown and renders it to a complete HTML document.
     *
     * @param markdown the Markdown source to render; {@code null} or blank yields
     *                 an empty HTML document
     * @return a full HTML document string wrapped in {@code <html><head>...</head><body>...</body></html>}
     */
    public static String render(String markdown) {
        return render(markdown, ThemeService.current());
    }

    /**
     * Parses the given Markdown and renders it to a complete HTML document
     * using the CSS for the explicitly-supplied theme.
     *
     * @param markdown the Markdown source to render; {@code null} or blank yields
     *                 an empty HTML document
     * @param theme    the theme whose CSS should be embedded; {@code null} falls
     *                 back to {@link ThemeService.Theme#DARK}
     * @return a full HTML document string wrapped in {@code <html><head>...</head><body>...</body></html>}
     */
    public static String render(String markdown, ThemeService.Theme theme) {
        String css = (theme == ThemeService.Theme.LIGHT) ? LIGHT_CSS : DARK_CSS;
        if (markdown == null || markdown.isBlank()) return wrapHtml("", css);
        Node document = PARSER.parse(markdown);
        return wrapHtml(RENDERER.render(document), css);
    }

    /**
     * Escapes HTML special characters and converts newlines to {@code <br>} tags.
     *
     * <p>Unlike {@link #render(String)}, this does not parse Markdown syntax —
     * it is intended for plain text that should appear verbatim with basic
     * HTML line breaks and entity encoding.</p>
     *
     * @param text the plain text to escape; {@code null} or blank yields an empty HTML document
     * @return a full HTML document string with the escaped text inside {@code <body>}
     */
    public static String renderPlain(String text) {
        return renderPlain(text, ThemeService.current());
    }

    /**
     * Same as {@link #renderPlain(String)} but embeds the CSS for the given theme.
     *
     * @param text  the plain text to escape; {@code null} or blank yields an empty HTML document
     * @param theme the theme whose CSS should be embedded; {@code null} falls back to DARK
     * @return a full HTML document string with the escaped text inside {@code <body>}
     */
    public static String renderPlain(String text, ThemeService.Theme theme) {
        String css = (theme == ThemeService.Theme.LIGHT) ? LIGHT_CSS : DARK_CSS;
        if (text == null || text.isBlank()) return wrapHtml("", css);
        String escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>");
        return wrapHtml(escaped, css);
    }

    /**
     * Renders markdown inside a collapsed {@code <details>} block with the given
     * summary label. Used for reasoning/thinking display — the card starts collapsed
     * and the user expands it to read the model's reasoning.
     *
     * @param summary  the visible summary text (HTML-escaped); shown when collapsed
     * @param markdown the markdown body; {@code null}/blank yields an empty document
     * @return a full HTML document with a collapsed {@code <details>} block
     */
    public static String renderCollapsible(String summary, String markdown) {
        if (markdown == null || markdown.isBlank()) return wrapHtml("");
        Node document = PARSER.parse(markdown);
        String inner = RENDERER.render(document);
        return wrapHtml(
            "<details class=\"sk-collapse\"><summary>" + escapeHtml(summary)
            + "</summary>" + inner + "</details>");
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String wrapHtml(String bodyContent) {
        return wrapHtml(bodyContent, cssFor(ThemeService.current()));
    }

    private static String wrapHtml(String bodyContent, String css) {
        return "<html><head><meta charset=\"UTF-8\"><style>" + css + "</style></head><body>"
            + bodyContent + "</body></html>";
    }

    private static String cssFor(ThemeService.Theme theme) {
        return (theme == ThemeService.Theme.LIGHT) ? LIGHT_CSS : DARK_CSS;
    }
}
