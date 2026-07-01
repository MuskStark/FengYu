package fan.summer.buildintool.email;

import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;
import fan.summer.api.theme.ThemeService;
import javafx.application.Platform;
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

    private static final PluginLogger log = LoggerFactory.getLogger(RichTextEditor.class);

    private final WebView webView;
    private boolean ready = false;

    public RichTextEditor() {
        log.debug("Initializing RichTextEditor");
        setSpacing(6);
        setStyle("-fx-background-color: transparent;");

        webView = new WebView();
        applyEditorChrome();
        webView.setMinHeight(200);
        webView.setPrefHeight(400);
        webView.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(webView, Priority.ALWAYS);

        HBox toolbar = buildToolbar();

        getChildren().addAll(toolbar, webView);

        webView.getEngine().getLoadWorker().stateProperty().addListener((obs, oldS, newS) -> {
            if (newS == javafx.concurrent.Worker.State.SUCCEEDED) {
                log.debug("RichTextEditor WebView loaded successfully");
                ready = true;
            }
        });
        webView.getEngine().loadContent(buildEditorHtml());

        // Re-theme the editor body when the app theme flips, WITHOUT losing the
        // user's content: snapshot the current HTML, rebuild the page in the new
        // palette, then restore. Also re-stamp the WebView container chrome
        // (background + border) so the frame stays visible in both themes —
        // the old dark-only CSS left white text on a light background in the
        // light theme (unreadable) and a missing border on a white panel.
        ThemeService.onChange(t -> Platform.runLater(() -> {
            applyEditorChrome();
            String snapshot = getHtml();
            webView.getEngine().loadContent(buildEditorHtml());
            // The new page's load completes asynchronously; re-apply the
            // snapshot once, then detach so the listener doesn't accumulate
            // one per theme switch.
            javafx.beans.value.ChangeListener<javafx.concurrent.Worker.State> restoreOnce = new javafx.beans.value.ChangeListener<>() {
                @Override
                public void changed(javafx.beans.value.ObservableValue<? extends javafx.concurrent.Worker.State> obs,
                                    javafx.concurrent.Worker.State o, javafx.concurrent.Worker.State n) {
                    if (n == javafx.concurrent.Worker.State.SUCCEEDED) {
                        webView.getEngine().getLoadWorker().stateProperty().removeListener(this);
                        setHtml(snapshot);
                    }
                }
            };
            webView.getEngine().getLoadWorker().stateProperty().addListener(restoreOnce);
        }));
    }

    /**
     * Stamps the WebView's JavaFX container with an opaque, theme-aware
     * background and border via inline style (which reliably overrides the
     * WebPage rendering surface — style-class borders on WebView can be flaky).
     * Colors mirror the {@code -sk-bg-elevated} / {@code -sk-border} tokens.
     * Called once at construction and again on every theme switch.
     */
    private void applyEditorChrome() {
        boolean light = ThemeService.current() == ThemeService.Theme.LIGHT;
        String bg     = light ? "#F7F8FA" : "#2B2B2B";
        String border = light ? "#DADCE0" : "#3C3F41";
        webView.setStyle(
                "-fx-background-color: " + bg + ";" +
                "-fx-border-color: " + border + ";" +
                "-fx-border-width: 1;" +
                "-fx-border-radius: 10; -fx-background-radius: 10;"
        );
    }

    private HBox buildToolbar() {
        ComboBox<String> fontCombo = new ComboBox<>(FXCollections.observableArrayList(
                "SansSerif", "Serif", "Monospace", "Arial", "Helvetica", "Georgia", "Courier New"
        ));
        fontCombo.setValue("SansSerif");
        fontCombo.getStyleClass().addAll("sk-surface", "sk-outlined", "sk-t1");
        fontCombo.setStyle(comboStyle());
        fontCombo.setTooltip(new Tooltip("字体"));
        fontCombo.setOnAction(e -> exec("fontName", fontCombo.getValue()));

        ComboBox<String> sizeCombo = new ComboBox<>(FXCollections.observableArrayList(
                "1", "2", "3", "4", "5", "6", "7"
        ));
        sizeCombo.setValue("3");
        sizeCombo.getStyleClass().addAll("sk-surface", "sk-outlined", "sk-t1");
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
        colorPicker.getStyleClass().add("sk-surface");
        colorPicker.setStyle(
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
        toolbar.getStyleClass().addAll("sk-surface", "sk-outlined");
        toolbar.setStyle(
                "-fx-border-width: 1;" +
                "-fx-border-radius: 8; -fx-background-radius: 8;"
        );
        return toolbar;
    }

    private Button toolbarButton(String label, String tooltip, boolean bold, boolean italic, boolean underline) {
        Button b = new Button(label);
        b.getStyleClass().addAll("sk-surface", "sk-outlined", "sk-t1");
        StringBuilder style = new StringBuilder(
                "-fx-border-width: 1;" +
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
        r.getStyleClass().add("sk-surface");
        return r;
    }

    private String comboStyle() {
        return "-fx-border-width: 1;" +
                "-fx-border-radius: 6; -fx-background-radius: 6;" +
                "-fx-font-size: 12px;";
    }

    private void exec(String command, String value) {
        if (!ready) {
            log.warn("exec called but editor not ready, command={}", command);
            return;
        }
        log.debug("Executing editor command: {} with value: {}", command, value);
        String js;
        if (value == null) {
            js = "applyCommand('" + command + "', null);";
        } else {
            js = "applyCommand('" + command + "', " + jsString(value) + ");";
        }
        try {
            webView.getEngine().executeScript(js);
        } catch (Exception e) {
            log.error("Failed to execute editor command {}: {}", command, e.getMessage());
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
        if (!ready) {
            log.debug("getHtml called but editor not ready, returning empty string");
            return "";
        }
        try {
            Object result = webView.getEngine().executeScript("document.getElementById('editor').innerHTML");
            return result != null ? result.toString() : "";
        } catch (Exception e) {
            log.error("Failed to get HTML from editor: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Returns the editor's plain-text content (with newlines preserved between blocks).
     */
    public String getPlainText() {
        if (!ready) {
            log.debug("getPlainText called but editor not ready, returning empty string");
            return "";
        }
        try {
            Object result = webView.getEngine().executeScript("document.getElementById('editor').innerText");
            return result != null ? result.toString() : "";
        } catch (Exception e) {
            log.error("Failed to get plain text from editor: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Replaces editor content with the given HTML. Safe to call before the editor finishes
     * loading — the content will be applied once load completes.
     */
    public void setHtml(String html) {
        String safe = html == null ? "" : html;
        log.debug("Setting editor HTML content ({} chars)", safe.length());
        Runnable apply = () -> {
            try {
                JSObject window = (JSObject) webView.getEngine().executeScript("window");
                window.setMember("__incomingHtml", safe);
                webView.getEngine().executeScript("document.getElementById('editor').innerHTML = window.__incomingHtml;");
                log.debug("HTML content applied successfully");
            } catch (Exception e) {
                log.error("Failed to set HTML content: {}", e.getMessage());
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
        boolean light = ThemeService.current() == ThemeService.Theme.LIGHT;
        // Opaque bg matching -sk-bg-elevated, so the editing surface never shows
        // JavaFX WebView's default white (which made white text invisible in the
        // dark theme, and blended with the page in the light theme).
        String bg = light ? "#F7F8FA" : "#2B2B2B";
        String textColor = light ? "#1E1E1E" : "#D0D0D0";
        String placeholderColor = light ? "#A0A4A8" : "#6B6F73";
        String quoteColor = light ? "#5A5D60" : "#9AA0A6";
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'><style>" +
                "html,body { margin:0; padding:0; height:100%; background: " + bg + "; }" +
                "body { font-family: -apple-system, 'Segoe UI', sans-serif; color: " + textColor + "; }" +
                "#editor {" +
                "  box-sizing: border-box;" +
                "  min-height: 100%;" +
                "  padding: 12px;" +
                "  outline: none;" +
                "  font-size: 14px;" +
                "  line-height: 1.6;" +
                "  background: " + bg + ";" +
                "  color: " + textColor + ";" +
                "  caret-color: #3574F0;" +
                "  white-space: pre-wrap;" +
                "}" +
                "#editor:empty:before {" +
                "  content: attr(data-placeholder);" +
                "  color: " + placeholderColor + ";" +
                "}" +
                "#editor a { color: #3574F0; }" +
                "#editor blockquote { border-left: 3px solid #3574F0; margin: 8px 0; padding-left: 12px; color: " + quoteColor + "; }" +
                "::selection { background: rgba(53,116,240,0.45); }" +
                "</style></head><body>" +
                "<div id='editor' contenteditable='true' data-placeholder='在此输入邮件正文，可使用上方工具栏设置格式...'></div>" +
                "<script>" +
                "function applyCommand(cmd, value) {" +
                "  document.getElementById('editor').focus();" +
                "  try { document.execCommand(cmd, false, value); } catch(e) {}" +
                "}" +
                "</script></body></html>";
    }
}
