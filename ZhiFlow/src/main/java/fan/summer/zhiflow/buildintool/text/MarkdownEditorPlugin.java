package fan.summer.zhiflow.buildintool.text;

import fan.summer.zhiflow.api.IconStyle;
import fan.summer.zhiflow.api.SwissKitJPlugin;
import fan.summer.zhiflow.api.ToolCategory;
import fan.summer.zhiflow.api.ToolType;
import fan.summer.zhiflow.api.i18n.I18n;
import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;
import fan.summer.zhiflow.api.theme.ThemeService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;

/**
 * Built-in plugin providing a split-pane Markdown editor with live HTML preview.
 *
 * <p>The editor pane supports basic Markdown syntax (headings, bold, italic,
 * inline code, blockquotes, and list items) which is rendered in real time
 * into a WebView using a dark-themed inline stylesheet.
 *
 * @see SwissKitJPlugin
 */
public class MarkdownEditorPlugin implements SwissKitJPlugin {

    private static final PluginLogger log = LoggerFactory.getLogger(MarkdownEditorPlugin.class);

    @Override public String getId()          { return "builtin.markdown"; }
    @Override public String getName()        { return I18n.get("builtin.markdown-editor.name"); }
    @Override public String getDescription() { return I18n.get("builtin.markdown-editor.desc"); }
    @Override public ToolCategory getCategory()    { return ToolCategory.TEXT; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getMdiIcon()    { return "language-markdown"; }
    @Override public IconStyle getIconStyle()   { return IconStyle.BLUE; }
    @Override public ToolType getType()        { return ToolType.BUILTIN; }

    /**
     * Creates and returns the plugin view containing a split editor/preview pane
     * with a pre-populated sample document.
     *
     * @return the root JavaFX Node for this plugin's UI
     */
    @Override
    public Node createView() {
        log.debug("Creating Markdown Editor view");
        TextArea editor = styledTextArea(
            "# Hello SwissKitJ\n\n" +
            "This is a **Markdown** editor.\n\n" +
            "- Supports *italic* and **bold**\n" +
            "- Supports lists\n" +
            "- Supports `code`\n\n" +
            "> Quote text\n"
        );

        WebView preview = new WebView();
        applyPreviewChrome(preview);

        // Render the editor markdown into the preview WebView using the CSS for
        // the CURRENT theme. Re-run on every text change AND on every theme
        // switch so the preview never gets stuck on one palette (the old code
        // baked in a dark-only stylesheet and was unreadable in light theme).
        Runnable render = () -> {
            log.debug("Rendering markdown preview");
            preview.getEngine().loadContent(buildPreviewPage(editor.getText()));
        };

        editor.textProperty().addListener((o, oldV, newV) -> render.run());
        // Re-stamp the WebView chrome on theme switch too (container bg + border
        // are read from the active theme), then re-render the body in the new palette.
        ThemeService.onChange(t -> Platform.runLater(() -> {
            applyPreviewChrome(preview);
            render.run();
        }));

        VBox left  = new VBox(6, sectionLabel(I18n.get("builtin.markdown.editor")),  editor);
        VBox right = new VBox(6, sectionLabel(I18n.get("builtin.markdown.preview")), preview);
        VBox.setVgrow(editor,   Priority.ALWAYS);
        VBox.setVgrow(preview,  Priority.ALWAYS);
        HBox.setHgrow(left,  Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);

        HBox root = new HBox(12, left, right);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: transparent;");
        HBox.setHgrow(root, Priority.ALWAYS);
        VBox.setVgrow(root, Priority.ALWAYS);
        render.run();
        return root;
    }

    /**
     * Builds a complete {@code <html>} preview document for the given Markdown
     * using the CSS palette of the currently active theme.
     *
     * <p>The body uses an <b>opaque</b> background that matches the app's
     * {@code -sk-bg-elevated} token (dark {@code #2B2B2B} / light {@code #F7F8FA}),
     * with a foreground color chosen for contrast. JavaFX's WebView paints an
     * opaque white rendering surface by default; using {@code transparent} there
     * exposes that white and — combined with a white foreground in light theme —
     * made text invisible. An explicit opaque bg also guarantees the text never
     * blends into the background in either theme.
     *
     * @param markdown the raw Markdown text to render
     * @return a full HTML document string
     */
    private static String buildPreviewPage(String markdown) {
        boolean light = ThemeService.current() == ThemeService.Theme.LIGHT;
        // These mirror the -sk-* tokens in zhiflow-common.css so the rendered
        // page matches the surrounding panel instead of guessing.
        String bodyBg     = light ? "#F7F8FA" : "#2B2B2B";
        String bodyColor  = light ? "#1E1E1E" : "#D0D0D0";
        String headingColor = light ? "#000000" : "#FFFFFF";
        String codeBg     = light ? "#EBECEF" : "#363636";
        String codeColor  = light ? "#1E1E1E" : "#D0D0D0";
        String quoteColor = light ? "#5A5D60" : "#9AA0A6";
        String quoteBorder= light ? "#C9CDD3" : "#3C3F41";
        String page =
            "<html><head><meta charset='UTF-8'><style>" +
            "body{font-family:-apple-system,sans-serif;color:" + bodyColor + ";" +
            "background:" + bodyBg + ";margin:0;padding:16px;font-size:14px;line-height:1.7;}" +
            "code{background:" + codeBg + ";color:" + codeColor + ";" +
            "border-radius:4px;padding:2px 6px;font-family:monospace;}" +
            "blockquote{border-left:3px solid " + quoteBorder + ";margin:0;" +
            "padding-left:16px;color:" + quoteColor + ";}" +
            "h1,h2,h3{color:" + headingColor + ";}" +
            "</style></head><body>" + mdToHtml(markdown) + "</body></html>";
        return page;
    }

    /**
     * Stamps the WebView's JavaFX container with the theme-aware background and
     * border so the area around/behind the rendered HTML matches the panel
     * (dark {@code #2B2B2B} / light {@code #F7F8FA} bg, {@code -sk-border} edge).
     * Called once at creation and again on every theme switch.
     */
    private static void applyPreviewChrome(WebView preview) {
        boolean light = ThemeService.current() == ThemeService.Theme.LIGHT;
        String bg     = light ? "#F7F8FA" : "#2B2B2B";
        String border = light ? "#DADCE0" : "#3C3F41";
        preview.setStyle(
            "-fx-background-color: " + bg + ";" +
            "-fx-border-color: " + border + ";" +
            "-fx-border-width: 1px;" +
            "-fx-border-radius: 10px; -fx-background-radius: 10px;"
        );
    }

    /**
     * Converts a Markdown string to basic HTML by applying regex replacements
     * for headings, bold, italic, inline code, blockquotes, and list items.
     *
     * @param md the raw Markdown text
     * @return the converted HTML string (no document wrapper)
     */
    private static String mdToHtml(String md) {
        return md
            .replaceAll("(?m)^### (.+)$", "<h3>$1</h3>")
            .replaceAll("(?m)^## (.+)$",  "<h2>$1</h2>")
            .replaceAll("(?m)^# (.+)$",   "<h1>$1</h1>")
            .replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>")
            .replaceAll("\\*(.+?)\\*",        "<em>$1</em>")
            .replaceAll("`(.+?)`",            "<code>$1</code>")
            .replaceAll("(?m)^> (.+)$",       "<blockquote>$1</blockquote>")
            .replaceAll("(?m)^- (.+)$",       "<li>$1</li>")
            .replaceAll("(?m)^$",             "<br/>");
    }

    /**
     * Creates a styled TextArea pre-filled with the initial text.
     *
     * @param initial the initial content of the text area
     * @return a styled, wrap-enabled TextArea that grows vertically in a VBox
     */
    private static TextArea styledTextArea(String initial) {
        TextArea ta = new TextArea(initial);
        ta.getStyleClass().addAll("sk-surface", "sk-outlined", "sk-t1");
        ta.setStyle(
            "-fx-border-width: 1;" +
            "-fx-border-radius: 10; -fx-background-radius: 10;" +
            "-fx-font-size: 13px; -fx-font-family: 'SF Mono','Consolas',monospace;" +
            "-fx-control-inner-background: transparent; -fx-highlight-fill: #3574F0;" +
            "-fx-padding: 12;"
        );
        ta.setWrapText(true);
        VBox.setVgrow(ta, Priority.ALWAYS);
        return ta;
    }

    /**
     * Creates an uppercase section label with muted styling.
     *
     * @param text the label text
     * @return a styled Label control
     */
    private static Label sectionLabel(String text) {
        Label l = new Label(text.toUpperCase());
        l.getStyleClass().add("sk-t3");
        l.setStyle(
            "-fx-font-size: 10px;" +
            "-fx-font-weight: bold; -fx-letter-spacing: 0.08em;"
        );
        return l;
    }
}
