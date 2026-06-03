package fan.summer.buildintool.browser;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;

import java.util.List;

/**
 * Extracts the current page state as a structured text snapshot for the planner LLM.
 *
 * <p>The snapshot includes the page URL, title, and a list of interactive elements
 * (links, buttons, inputs, selects, textareas) with their CSS selectors and text content.
 * The format is compact but informative enough for the LLM to decide which element
 * to interact with.</p>
 */
public final class PageSnapshot {

    private PageSnapshot() {}

    /**
     * Captures a text snapshot of the current page state.
     *
     * @param session the active browser session
     * @return a structured text representation of the page
     */
    public static String capture(BrowserSession session) {
        Page page = session.page();
        StringBuilder sb = new StringBuilder();

        sb.append("=== Page State ===\n");
        sb.append("URL: ").append(session.getCurrentUrl()).append("\n");
        sb.append("Title: ").append(session.getTitle()).append("\n");

        // Visible page text (truncated)
        String bodyText = getVisibleText(page);
        sb.append("\n--- Visible Text ---\n");
        sb.append(bodyText).append("\n");

        // Interactive elements
        sb.append("\n--- Interactive Elements ---\n");
        sb.append(buildInteractiveElementsList(page));

        sb.append("\n=== End Page State ===\n");
        return sb.toString();
    }

    /**
     * Extracts visible text content from the page body, truncated to fit LLM context.
     */
    private static String getVisibleText(Page page) {
        try {
            String text = page.textContent("body");
            if (text == null) return "(no visible text)";
            String cleaned = text.replaceAll("\\s+", " ").trim();
            if (cleaned.length() > 6000) {
                return cleaned.substring(0, 6000) + " ... [truncated]";
            }
            return cleaned;
        } catch (Exception e) {
            return "(failed to extract text: " + e.getMessage() + ")";
        }
    }

    /**
     * Builds a list of interactive elements with their selectors and text content.
     * Targets: a, button, input, select, textarea, [role="button"], [role="link"]
     */
    private static String buildInteractiveElementsList(Page page) {
        StringBuilder sb = new StringBuilder();
        String[] selectors = {
            "a[href]",
            "button",
            "input",
            "select",
            "textarea",
            "[role='button']",
            "[role='link']"
        };

        int index = 0;
        for (String selector : selectors) {
            List<ElementHandle> elements;
            try {
                elements = page.querySelectorAll(selector);
            } catch (Exception e) {
                continue;
            }

            for (ElementHandle el : elements) {
                try {
                    if (!el.isVisible()) continue;

                    String tag = el.evaluate("el => el.tagName.toLowerCase()").toString();
                    String text = el.textContent();
                    if (text != null) text = text.replaceAll("\\s+", " ").trim();
                    if (text != null && text.length() > 100) text = text.substring(0, 100) + "...";

                    String type = el.getAttribute("type");
                    String placeholder = el.getAttribute("placeholder");
                    String name = el.getAttribute("name");
                    String id = el.getAttribute("id");
                    String href = el.getAttribute("href");
                    String ariaLabel = el.getAttribute("aria-label");

                    // Build a best-effort CSS selector for this element
                    String cssSelector = buildSelector(tag, id, name, type);

                    sb.append("[").append(index++).append("] ");
                    sb.append("<").append(tag);
                    if (type != null) sb.append(" type=\"").append(type).append("\"");
                    if (id != null) sb.append(" id=\"").append(id).append("\"");
                    if (name != null) sb.append(" name=\"").append(name).append("\"");
                    if (placeholder != null) sb.append(" placeholder=\"").append(placeholder).append("\"");
                    if (ariaLabel != null) sb.append(" aria-label=\"").append(ariaLabel).append("\"");
                    if (href != null) sb.append(" href=\"").append(truncate(href, 80)).append("\"");
                    sb.append(">");

                    if (text != null && !text.isEmpty()) {
                        sb.append(" \"").append(truncate(text, 80)).append("\"");
                    }
                    sb.append("  → selector: ").append(cssSelector);
                    sb.append("\n");

                    // Limit to 50 interactive elements to keep snapshot compact
                    if (index >= 50) {
                        sb.append("... (truncated, more elements on page)\n");
                        return sb.toString();
                    }
                } catch (Exception ignored) {
                    // Skip elements that throw during property access
                }
            }
        }

        if (index == 0) {
            sb.append("(no interactive elements found)\n");
        }

        return sb.toString();
    }

    /**
     * Builds a CSS selector string for targeting the element.
     * Prefers #id, then [name='x'], then tag[type='x'], then generic tag.
     */
    private static String buildSelector(String tag, String id, String name, String type) {
        if (id != null && !id.isEmpty()) return "#" + id;
        if (name != null && !name.isEmpty()) return tag + "[name='" + name + "']";
        if (type != null && !type.isEmpty()) return tag + "[type='" + type + "']";
        return tag;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
