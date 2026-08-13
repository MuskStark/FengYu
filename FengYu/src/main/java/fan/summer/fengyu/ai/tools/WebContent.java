package fan.summer.fengyu.ai.tools;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small dependency-free HTML-to-text helpers for bounded tool responses. */
final class WebContent {

    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern SCRIPT_STYLE = Pattern.compile("(?is)<(script|style|noscript)[^>]*>.*?</\\1>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");

    private WebContent() {}

    static String title(String html) {
        Matcher matcher = TITLE.matcher(html == null ? "" : html);
        return matcher.find() ? normalize(decode(matcher.group(1))) : "";
    }

    static String text(String value, String contentType) {
        if (value == null) return "";
        if (contentType != null && (contentType.contains("json") || contentType.contains("text/plain"))) {
            return value.trim();
        }
        String withoutCode = SCRIPT_STYLE.matcher(value).replaceAll(" ");
        return normalize(decode(TAG.matcher(withoutCode).replaceAll(" ")));
    }

    static String decode(String value) {
        if (value == null) return "";
        return value.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
    }

    static String normalize(String value) {
        return value.replaceAll("[\\p{Z}\\s]+", " ").trim();
    }
}
