package fan.summer.zhiflow.buildintool.dev;

import fan.summer.zhiflow.api.IconStyle;
import fan.summer.zhiflow.api.ZhiFlowPlugin;
import fan.summer.zhiflow.api.ToolCategory;
import fan.summer.zhiflow.api.ToolType;
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.i18n.I18n;
import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;
import fan.summer.zhiflow.ai.tools.BuiltinHashTool;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Built-in plugin for calculating cryptographic hashes (MD5, SHA-1, SHA-256, SHA-512)
 * from free-form text input.
 *
 * <p>Displays results in a scrollable list with per-hash copy-to-clipboard buttons.
 * Each hash algorithm is computed concurrently on the UI thread on button click.
 *
 * @see ZhiFlowPlugin
 */
public class HashCalculatorPlugin implements ZhiFlowPlugin {

    private static final PluginLogger log = LoggerFactory.getLogger(HashCalculatorPlugin.class);

    private static final String[][] ALGOS = {
        {"MD5", "MD5"}, {"SHA-1", "SHA-1"}, {"SHA-256", "SHA-256"}, {"SHA-512", "SHA-512"}
    };

    @Override public String getId()          { return "builtin.hash"; }
    @Override public String getName()        { return I18n.get("builtin.hash-calculator.name"); }
    @Override public String getDescription() { return I18n.get("builtin.hash-calculator.desc"); }
    @Override public ToolCategory getCategory()    { return ToolCategory.DEV; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getMdiIcon()    { return "key-variant"; }
    @Override public IconStyle getIconStyle()   { return IconStyle.AMBER; }
    @Override public ToolType getType()        { return ToolType.BUILTIN; }

    @Override
    public List<AiTool> aiTools() {
        return List.of(new BuiltinHashTool());
    }

    /**
     * Creates and returns the plugin view containing a text input area,
     * a Calculate button, and a results list with per-hash copy buttons.
     *
     * @return the root JavaFX Node for this plugin's UI
     */
    @Override
    public Node createView() {
        log.debug("Creating Hash Calculator view");
        TextArea input = styledTextArea("Input text...");
        VBox results   = new VBox(8);

        Button calcBtn = actionButton(I18n.get("builtin.hash.calculate"), "#3574F0");
        calcBtn.setOnAction(e -> {
            log.info("Calculate hash button clicked");
            results.getChildren().clear();
            for (String[] algo : ALGOS) {
                try {
                    MessageDigest md   = MessageDigest.getInstance(algo[1]);
                    byte[]        hash = md.digest(input.getText().getBytes(StandardCharsets.UTF_8));
                    StringBuilder hex  = new StringBuilder();
                    for (byte b : hash) hex.append(String.format("%02x", b));
                    results.getChildren().add(hashRow(algo[0], hex.toString()));
                    log.debug("Calculated {} hash successfully", algo[0]);
                } catch (Exception ex) {
                    log.error("Failed to calculate {} hash: {}", algo[0], ex.getMessage());
                    results.getChildren().add(hashRow(algo[0], "Error"));
                }
            }
        });

        VBox root = new VBox(12, sectionLabel(I18n.get("builtin.hash.inputText")), input, calcBtn, sectionLabel(I18n.get("builtin.hash.result")), results);
        VBox.setVgrow(input, Priority.ALWAYS);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: transparent;");
        return root;
    }

    /**
     * Builds a single hash result row showing algorithm name, hash value,
     * and a copy button that copies the value to the system clipboard.
     *
     * @param algo  the algorithm display name (e.g. "MD5")
     * @param value the hexadecimal hash string
     * @return an HBox representing the result row
     */
    private HBox hashRow(String algo, String value) {
        Label algoLabel = new Label(algo);
        algoLabel.getStyleClass().add("sk-t2");
        algoLabel.setStyle(
            "-fx-font-size: 11px;" +
            "-fx-min-width: 60px; -fx-font-family: 'SF Mono','Consolas',monospace;"
        );
        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().add("sk-t1");
        valueLabel.setStyle(
            "-fx-font-size: 11px;" +
            "-fx-font-family: 'SF Mono','Consolas',monospace;"
        );
        valueLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(valueLabel, Priority.ALWAYS);

        Button copy = new Button(I18n.get("builtin.hash.copy"));
        copy.getStyleClass().addAll("sk-surface", "sk-t2");
        copy.setStyle(
            "-fx-border-width: 0;" +
            "-fx-font-size: 10px;" +
            "-fx-background-radius: 4; -fx-cursor: hand; -fx-padding: 2 8 2 8;"
        );
        copy.setOnAction(e -> {
            log.debug("Copying {} hash to clipboard", algo);
            ClipboardContent cc = new ClipboardContent();
            cc.putString(value);
            Clipboard.getSystemClipboard().setContent(cc);
            copy.setText("✓");
            PauseTransition pt = new PauseTransition(Duration.seconds(1.5));
            pt.setOnFinished(ev -> copy.setText(I18n.get("builtin.hash.copy")));
            pt.play();
        });

        HBox row = new HBox(10, algoLabel, valueLabel, copy);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("sk-surface");
        row.setStyle(
            "-fx-background-radius: 8; -fx-padding: 10 12 10 12;"
        );
        return row;
    }

    /**
     * Creates a styled TextArea with monospace font and the given prompt text.
     *
     * @param prompt the placeholder text shown when the area is empty
     * @return a styled, wrap-enabled TextArea that grows vertically in a VBox
     */
    private static TextArea styledTextArea(String prompt) {
        TextArea ta = new TextArea();
        ta.setPromptText(prompt);
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
     * Creates an action button with the given text and background color.
     *
     * @param text the button label
     * @param bg   the CSS background color value
     * @return a styled Button control
     */
    private static Button actionButton(String text, String bg) {
        Button btn = new Button(text);
        btn.getStyleClass().add("sk-t1");
        StringBuilder style = new StringBuilder();
        if (bg != null && bg.startsWith("#")) {
            style.append("-fx-background-color: ").append(bg).append(";");
        } else {
            btn.getStyleClass().add("sk-surface");
        }
        style.append("-fx-font-size: 13px;");
        style.append("-fx-background-radius: 8; -fx-border-width: 0;");
        style.append("-fx-padding: 8 18 8 18; -fx-cursor: hand;");
        btn.setStyle(style.toString());
        return btn;
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
