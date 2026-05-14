package fan.summer.buildintool.text;

import fan.summer.api.SwissKitJPlugin;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;

public class MarkdownEditorPlugin implements SwissKitJPlugin {

    @Override public String getId()          { return "builtin.markdown"; }
    @Override public String getName()        { return "Markdown"; }
    @Override public String getDescription() { return "Real-time Markdown editor with preview"; }
    @Override public String getCategory()    { return "text"; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getIconText()    { return "M↓"; }
    @Override public String getIconStyle()   { return "ic-blue"; }
    @Override public String getType()        { return "builtin"; }

    @Override
    public Node createView() {
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
            String html = mdToHtml(editor.getText());
            String page =
                "<html><head><style>" +
                "body{font-family:-apple-system,sans-serif;color:rgba(255,255,255,0.88);" +
                "background:transparent;padding:16px;font-size:14px;line-height:1.7;}" +
                "code{background:rgba(255,255,255,0.1);border-radius:4px;padding:2px 6px;" +
                "font-family:monospace;}" +
                "blockquote{border-left:3px solid #5b8cf7;margin:0;padding-left:16px;" +
                "color:rgba(255,255,255,0.5);}" +
                "h1,h2,h3{color:rgba(255,255,255,0.95);}" +
                "</style></head><body>" + html + "</body></html>";
            preview.getEngine().loadContent(page);
        };

        editor.textProperty().addListener((o, oldV, newV) -> render.run());

        VBox left  = new VBox(6, sectionLabel("Editor"),  editor);
        VBox right = new VBox(6, sectionLabel("Preview"), preview);
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

    private static TextArea styledTextArea(String initial) {
        TextArea ta = new TextArea(initial);
        ta.setStyle(
            "-fx-background-color: rgba(255,255,255,0.04);" +
            "-fx-border-color: rgba(255,255,255,0.10); -fx-border-width: 1;" +
            "-fx-border-radius: 10; -fx-background-radius: 10;" +
            "-fx-text-fill: rgba(255,255,255,0.88);" +
            "-fx-font-size: 13px; -fx-font-family: 'SF Mono','Consolas',monospace;" +
            "-fx-control-inner-background: transparent; -fx-highlight-fill: #5b8cf7;" +
            "-fx-padding: 12;"
        );
        ta.setWrapText(true);
        VBox.setVgrow(ta, Priority.ALWAYS);
        return ta;
    }

    private static Label sectionLabel(String text) {
        Label l = new Label(text.toUpperCase());
        l.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.28); -fx-font-size: 10px;" +
            "-fx-font-weight: bold; -fx-letter-spacing: 0.08em;"
        );
        return l;
    }
}
