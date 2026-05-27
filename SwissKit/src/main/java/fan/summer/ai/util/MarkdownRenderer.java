package fan.summer.ai.util;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

public final class MarkdownRenderer {

    private static final Parser PARSER = Parser.builder().build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().build();

    private static final String CSS = """
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
        a { color: #5b8cf7; }
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
        """;

    private MarkdownRenderer() {}

    public static String render(String markdown) {
        if (markdown == null || markdown.isBlank()) return wrapHtml("");
        Node document = PARSER.parse(markdown);
        return wrapHtml(RENDERER.render(document));
    }

    public static String renderPlain(String text) {
        if (text == null || text.isBlank()) return wrapHtml("");
        String escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>");
        return wrapHtml(escaped);
    }

    private static String wrapHtml(String bodyContent) {
        return "<html><head><style>" + CSS + "</style></head><body>"
            + bodyContent + "</body></html>";
    }
}
