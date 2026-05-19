package fan.summer.buildintool.email;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;

/**
 * Rich text HTML editor backed by an embedded WebView with a contenteditable body and a
 * formatting toolbar (bold / italic / underline / font family / size / color / alignment).
 * The editor content can be retrieved as HTML via {@link #getHtml()} and replaced via
 * {@link #setHtml(String)}.
 *
 * Formatting is applied via the document.execCommand API on the embedded page, so all
 * caret/selection state is handled natively by the WebView.
 */
public class RichTextEditor extends VBox {

    private final WebView webView;
    private boolean ready = false;

    public RichTextEditor() {
        setSpacing(6);
        setStyle("-fx-background-color: transparent;");

        webView = new WebView();
        webView.setStyle(
                "-fx-background-color: rgba(255,255,255,0.04);" +
                "-fx-border-color: rgba(255,255,255,0.10); -fx-border-width: 1;" +
                "-fx-border-radius: 10; -fx-background-radius: 10;"
        );
        VBox.setVgrow(webView, Priority.ALWAYS);

        HBox toolbar = buildToolbar();

        getChildren().addAll(toolbar, webView);

        webView.getEngine().getLoadWorker().stateProperty().addListener((obs, oldS, newS) -> {
            if (newS == javafx.concurrent.Worker.State.SUCCEEDED) {
                ready = true;
            }
        });
        webView.getEngine().loadContent(buildEditorHtml());
    }

    private HBox buildToolbar() {
        ComboBox<String> fontCombo = new ComboBox<>(FXCollections.observableArrayList(
                "SansSerif", "Serif", "Monospace", "Arial", "Helvetica", "Georgia", "Courier New"
        ));
        fontCombo.setValue("SansSerif");
        fontCombo.setStyle(comboStyle());
        fontCombo.setTooltip(new Tooltip("字体"));
        fontCombo.setOnAction(e -> exec("fontName", fontCombo.getValue()));

        ComboBox<String> sizeCombo = new ComboBox<>(FXCollections.observableArrayList(
                "1", "2", "3", "4", "5", "6", "7"
        ));
        sizeCombo.setValue("3");
        sizeCombo.setStyle(comboStyle());
        sizeCombo.setTooltip(new Tooltip("字号 (1=小, 7=大)"));
        sizeCombo.setOnAction(e -> exec("fontSize", sizeCombo.getValue()));

        Button bold = toolbarButton("B", "粗体", true, false, false);
        bold.setOnAction(e -> exec("bold", null));

        Button italic = toolbarButton("I", "斜体", false, true, false);
        italic.setOnAction(e -> exec("italic", null));

        Button underline = toolbarButton("U", "下划线", false, false, true);
        underline.setOnAction(e -> exec("underline", null));

        ColorPicker colorPicker = new ColorPicker(Color.WHITE);
        colorPicker.setStyle(
                "-fx-background-color: rgba(255,255,255,0.07);" +
                "-fx-color-label-visible: false;"
        );
        colorPicker.setTooltip(new Tooltip("字体颜色"));
        colorPicker.setOnAction(e -> exec("foreColor", toHex(colorPicker.getValue())));

        Button alignLeft = toolbarButton("⯇", "左对齐", false, false, false);
        alignLeft.setOnAction(e -> exec("justifyLeft", null));

        Button alignCenter = toolbarButton("≡", "居中", false, false, false);
        alignCenter.setOnAction(e -> exec("justifyCenter", null));

        Button alignRight = toolbarButton("⯈", "右对齐", false, false, false);
        alignRight.setOnAction(e -> exec("justifyRight", null));

        Button list = toolbarButton("•", "项目符号", false, false, false);
        list.setOnAction(e -> exec("insertUnorderedList", null));

        Button orderedList = toolbarButton("1.", "编号列表", false, false, false);
        orderedList.setOnAction(e -> exec("insertOrderedList", null));

        Button clear = toolbarButton("✕", "清除格式", false, false, false);
        clear.setOnAction(e -> exec("removeFormat", null));

        HBox toolbar = new HBox(6,
                fontCombo, sizeCombo,
                separator(),
                bold, italic, underline, colorPicker,
                separator(),
                alignLeft, alignCenter, alignRight,
                separator(),
                list, orderedList,
                separator(),
                clear
        );
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6, 8, 6, 8));
        toolbar.setStyle(
                "-fx-background-color: rgba(255,255,255,0.04);" +
                "-fx-border-color: rgba(255,255,255,0.10); -fx-border-width: 1;" +
                "-fx-border-radius: 8; -fx-background-radius: 8;"
        );
        return toolbar;
    }

    private Button toolbarButton(String label, String tooltip, boolean bold, boolean italic, boolean underline) {
        Button b = new Button(label);
        StringBuilder style = new StringBuilder(
                "-fx-background-color: rgba(255,255,255,0.06);" +
                "-fx-border-color: rgba(255,255,255,0.10); -fx-border-width: 1;" +
                "-fx-text-fill: rgba(255,255,255,0.85);" +
                "-fx-background-radius: 6; -fx-border-radius: 6;" +
                "-fx-padding: 4 10 4 10; -fx-cursor: hand;" +
                "-fx-font-size: 13px;"
        );
        if (bold) style.append("-fx-font-weight: bold;");
        if (italic) style.append("-fx-font-style: italic;");
        if (underline) style.append("-fx-underline: true;");
        b.setStyle(style.toString());
        b.setTooltip(new Tooltip(tooltip));
        b.setMinWidth(Region.USE_PREF_SIZE);
        return b;
    }

    private Region separator() {
        Region r = new Region();
        r.setPrefWidth(1);
        r.setPrefHeight(20);
        r.setStyle("-fx-background-color: rgba(255,255,255,0.12);");
        return r;
    }

    private String comboStyle() {
        return "-fx-background-color: rgba(255,255,255,0.06);" +
                "-fx-border-color: rgba(255,255,255,0.10); -fx-border-width: 1;" +
                "-fx-border-radius: 6; -fx-background-radius: 6;" +
                "-fx-text-fill: rgba(255,255,255,0.85); -fx-font-size: 12px;";
    }

    private void exec(String command, String value) {
        if (!ready) return;
        String js;
        if (value == null) {
            js = "applyCommand('" + command + "', null);";
        } else {
            js = "applyCommand('" + command + "', " + jsString(value) + ");";
        }
        try {
            webView.getEngine().executeScript(js);
        } catch (Exception ignored) {
        }
    }

    private String jsString(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private String toHex(Color c) {
        return String.format("#%02X%02X%02X",
                (int) (c.getRed() * 255),
                (int) (c.getGreen() * 255),
                (int) (c.getBlue() * 255));
    }

    /**
     * Returns the current HTML content of the editor body. If the editor has not finished
     * loading yet, returns an empty string.
     */
    public String getHtml() {
        if (!ready) return "";
        try {
            Object result = webView.getEngine().executeScript("document.getElementById('editor').innerHTML");
            return result != null ? result.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Returns the editor's plain-text content (with newlines preserved between blocks).
     */
    public String getPlainText() {
        if (!ready) return "";
        try {
            Object result = webView.getEngine().executeScript("document.getElementById('editor').innerText");
            return result != null ? result.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Replaces editor content with the given HTML. Safe to call before the editor finishes
     * loading — the content will be applied once load completes.
     */
    public void setHtml(String html) {
        String safe = html == null ? "" : html;
        Runnable apply = () -> {
            try {
                JSObject window = (JSObject) webView.getEngine().executeScript("window");
                window.setMember("__incomingHtml", safe);
                webView.getEngine().executeScript("document.getElementById('editor').innerHTML = window.__incomingHtml;");
            } catch (Exception ignored) {
            }
        };
        if (ready) {
            apply.run();
        } else {
            webView.getEngine().getLoadWorker().stateProperty().addListener((obs, o, n) -> {
                if (n == javafx.concurrent.Worker.State.SUCCEEDED) apply.run();
            });
        }
    }

    private String buildEditorHtml() {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'><style>" +
                "html,body { margin:0; padding:0; height:100%; background: transparent; }" +
                "body { font-family: -apple-system, 'Segoe UI', sans-serif; color: #e6e6e6; }" +
                "#editor {" +
                "  box-sizing: border-box;" +
                "  min-height: 100%;" +
                "  padding: 12px;" +
                "  outline: none;" +
                "  font-size: 14px;" +
                "  line-height: 1.6;" +
                "  background: transparent;" +
                "  color: rgba(255,255,255,0.90);" +
                "  caret-color: #5b8cf7;" +
                "  white-space: pre-wrap;" +
                "}" +
                "#editor:empty:before {" +
                "  content: attr(data-placeholder);" +
                "  color: rgba(255,255,255,0.30);" +
                "}" +
                "#editor a { color: #5b8cf7; }" +
                "#editor blockquote { border-left: 3px solid #5b8cf7; margin: 8px 0; padding-left: 12px; color: rgba(255,255,255,0.65); }" +
                "::selection { background: rgba(91,140,247,0.45); }" +
                "</style></head><body>" +
                "<div id='editor' contenteditable='true' data-placeholder='在此输入邮件正文，可使用上方工具栏设置格式...'></div>" +
                "<script>" +
                "function applyCommand(cmd, value) {" +
                "  document.getElementById('editor').focus();" +
                "  try { document.execCommand(cmd, false, value); } catch(e) {}" +
                "}" +
                "document.getElementById('editor').addEventListener('paste', function(e) {" +
                "  e.preventDefault();" +
                "  var text = (e.clipboardData || window.clipboardData).getData('text/plain');" +
                "  document.execCommand('insertText', false, text);" +
                "});" +
                "</script></body></html>";
    }
}
