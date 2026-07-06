package fan.summer.zhiflow.buildintool.dev;

import fan.summer.zhiflow.api.IconStyle;
import fan.summer.zhiflow.api.ZhiFlowPlugin;
import fan.summer.zhiflow.api.ToolCategory;
import fan.summer.zhiflow.api.ToolType;
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.i18n.I18n;
import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;
import fan.summer.zhiflow.ai.tools.BuiltinBase64Tool;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Built-in plugin for encoding and decoding Base64 strings.
 * Provides a two-pane interface with input/output text areas and
 * Encode, Decode, and Swap action buttons.
 *
 * @see ZhiFlowPlugin
 */
public class Base64Plugin implements ZhiFlowPlugin {

    private static final PluginLogger log = LoggerFactory.getLogger(Base64Plugin.class);

    @Override public String getId()          { return "builtin.base64"; }
    @Override public String getName()        { return I18n.get("builtin.base64.name"); }
    @Override public String getDescription() { return I18n.get("builtin.base64.desc"); }
    @Override public ToolCategory getCategory()    { return ToolCategory.DEV; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getMdiIcon()    { return "base64"; }
    @Override public IconStyle getIconStyle()   { return IconStyle.TEAL; }
    @Override public ToolType getType()        { return ToolType.BUILTIN; }

    @Override
    public List<AiTool> aiTools() {
        return List.of(new BuiltinBase64Tool());
    }

    /**
     * Creates and returns the plugin view containing input/output text areas
     * and Encode, Decode, Swap buttons.
     *
     * @return the root JavaFX Node for this plugin's UI
     */
    @Override
    public Node createView() {
        log.debug("Creating Base64 encode/decode view");
        TextArea input  = styledTextArea("Input text...");
        TextArea output = styledTextArea("");
        output.setEditable(false);

        Button encodeBtn = actionButton(I18n.get("builtin.base64.encode"),  "#3574F0");
        Button decodeBtn = actionButton(I18n.get("builtin.base64.decode"),  null);
        Button swapBtn   = actionButton("↕ Swap",    null);

        encodeBtn.setOnAction(e -> {
            try {
                log.debug("Encoding text to Base64");
                byte[] encoded = Base64.getEncoder().encode(input.getText().getBytes(StandardCharsets.UTF_8));
                output.setText(new String(encoded));
            } catch (Exception ex) {
                log.error("Failed to encode Base64: {}", ex.getMessage());
                output.setText("Error: " + ex.getMessage());
            }
        });
        decodeBtn.setOnAction(e -> {
            try {
                log.debug("Decoding Base64 to text");
                byte[] decoded = Base64.getDecoder().decode(input.getText().trim());
                output.setText(new String(decoded, StandardCharsets.UTF_8));
            } catch (Exception ex) {
                log.warn("Invalid Base64 input: {}", ex.getMessage());
                output.setText("❌ Invalid Base64");
            }
        });
        swapBtn.setOnAction(e -> {
            log.debug("Swapping input and output fields");
            String tmp = input.getText();
            input.setText(output.getText());
            output.setText(tmp);
        });

        HBox btnRow = new HBox(8, encodeBtn, decodeBtn, swapBtn);
        btnRow.setPadding(new Insets(0, 0, 12, 0));

        VBox left  = new VBox(6, sectionLabel(I18n.get("builtin.base64.input")),  input);
        VBox right = new VBox(6, sectionLabel(I18n.get("builtin.base64.output")), output);
        VBox.setVgrow(input,  Priority.ALWAYS);
        VBox.setVgrow(output, Priority.ALWAYS);
        HBox.setHgrow(left,   Priority.ALWAYS);
        HBox.setHgrow(right,  Priority.ALWAYS);

        HBox editors = new HBox(12, left, right);
        VBox.setVgrow(editors, Priority.ALWAYS);

        VBox root = new VBox(12, btnRow, editors);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: transparent;");
        return root;
    }

    /**
     * Creates a styled TextArea with the given prompt text.
     *
     * @param prompt the placeholder text shown when the area is empty
     * @return a styled, wrap-enabled TextArea
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
