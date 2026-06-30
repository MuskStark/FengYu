package fan.summer.buildintool.text;

import fan.summer.api.IconStyle;
import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.ToolCategory;
import fan.summer.api.ToolType;
import fan.summer.api.i18n.I18n;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;
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
        preview.setStyle("-fx-background-color: transparent;");

        Runnable render = () -> {
            log.debug("Rendering markdown preview");
            String html = mdToHtml(editor.getText());
            String page =
                "<html><head><style>" +
                "body{font-family:-apple-system,sans-serif;color:rgba(255,255,255,0.88);" +
                "background:transparent;padding:16px;font-size:14px;line-height:1.7;}" +
                "code{background:rgba(255,255,255,0.1);border-radius:4px;padding:2px 6px;" +
                "font-family:monospace;}" +
                "blockquote{border-left:3px solid #3574F0;margin:0;padding-left:16px;" +
                "color:rgba(255,255,255,0.5);}" +
                "h1,h2,h3{color:rgba(255,255,255,0.95);}" +
                "</style></head><body>" + html + "</body></html>";
            preview.getEngine().loadContent(page);
        };

        editor.textProperty().addListener((o, oldV, newV) -> render.run());

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
     * Converts a Markdown string to basic HTML by applying regex replacements
     * for headings, bold, italic, inline code, blockquotes, and list items.
     *
     * @param md the raw Markdown text
     * @return the converted HTML string (no document wrapper)
     */
    private String mdToHtml(String md) {
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
        ta.setStyle(
            "-fx-background-color: rgba(255,255,255,0.04);" +
            "-fx-border-color: rgba(255,255,255,0.10); -fx-border-width: 1;" +
            "-fx-border-radius: 10; -fx-background-radius: 10;" +
            "-fx-text-fill: rgba(255,255,255,0.88);" +
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
        l.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.28); -fx-font-size: 10px;" +
            "-fx-font-weight: bold; -fx-letter-spacing: 0.08em;"
        );
        return l;
    }
}
