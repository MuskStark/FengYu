package fan.summer.fengyu.plugin.email.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Sanitizes rich text to the conservative HTML/CSS subset supported by email clients. */
public final class EmailHtmlSanitizer {
    private static final Safelist ALLOWED = new Safelist()
        .addTags("p", "br", "h1", "h2", "h3", "h4", "h5", "h6", "strong", "b", "em", "i", "u",
            "ol", "ul", "li", "a", "table", "thead", "tbody", "tr", "th", "td", "blockquote", "span")
        .addAttributes("a", "href", "title")
        .addProtocols("a", "href", "http", "https", "mailto")
        .addAttributes(":all", "style");

    private static final Set<String> SAFE_STYLE_NAMES = Set.of(
        "text-align", "color", "background-color", "font-size", "font-weight",
        "font-style", "text-decoration", "border", "border-color", "border-style", "border-width");

    public String sanitize(String html) {
        Document dirty = Jsoup.parseBodyFragment(html == null ? "" : html);
        dirty.select("[class]").removeAttr("class");
        dirty.select("[style]").forEach(element -> {
            String style = safeStyle(element.attr("style"));
            if (style.isBlank()) element.removeAttr("style");
            else element.attr("style", style);
        });
        return Jsoup.clean(dirty.body().html(), "", ALLOWED,
            new Document.OutputSettings().prettyPrint(false));
    }

    public String toPlainText(String html) {
        return Jsoup.parseBodyFragment(sanitize(html)).text().trim();
    }

    private static String safeStyle(String input) {
        if (input == null || input.isBlank()) return "";
        List<String> safe = new ArrayList<>();
        for (String declaration : input.split(";")) {
            int colon = declaration.indexOf(':');
            if (colon < 1) continue;
            String name = declaration.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = declaration.substring(colon + 1).trim();
            String folded = value.toLowerCase(Locale.ROOT);
            if (!SAFE_STYLE_NAMES.contains(name) || value.isBlank() || value.length() > 80
                    || folded.contains("url(") || folded.contains("expression")
                    || folded.contains("javascript:")) continue;
            safe.add(name + ": " + value);
        }
        return String.join("; ", safe);
    }
}
