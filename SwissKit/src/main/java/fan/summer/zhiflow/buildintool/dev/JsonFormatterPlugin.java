package fan.summer.zhiflow.buildintool.dev;

import fan.summer.zhiflow.api.IconStyle;
import fan.summer.zhiflow.api.SwissKitJPlugin;
import fan.summer.zhiflow.api.ToolCategory;
import fan.summer.zhiflow.api.ToolType;
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.i18n.I18n;
import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;
import fan.summer.zhiflow.ai.tools.BuiltinJsonFormatTool;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Built-in plugin for formatting and validating JSON strings.
 * Provides three actions: Pretty Print, Compress, and Clear.
 *
 * <p>The pretty-print implementation performs its own minimal formatting
 * without external dependencies, handling braces, brackets, colons, and commas.
 *
 * @see SwissKitJPlugin
 */
public class JsonFormatterPlugin implements SwissKitJPlugin {

    private static final PluginLogger log = LoggerFactory.getLogger(JsonFormatterPlugin.class);

    @Override public String getId()          { return "builtin.json-formatter"; }
    @Override public String getName()        { return I18n.get("builtin.json-formatter.name"); }
    @Override public String getDescription() { return I18n.get("builtin.json-formatter.desc"); }
    @Override public ToolCategory getCategory()    { return ToolCategory.DEV; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getMdiIcon()    { return "code-json"; }
    @Override public IconStyle getIconStyle()   { return IconStyle.BLUE; }
    @Override public ToolType getType()        { return ToolType.BUILTIN; }

    @Override
    public List<AiTool> aiTools() {
        return List.of(new BuiltinJsonFormatTool());
    }

    /**
     * Creates and returns the plugin view containing input/output text areas
     * and Format, Compress, Clear buttons.
     *
     * @return the root JavaFX Node for this plugin's UI
     */
    @Override
    public Node createView() {
        log.debug("Creating JSON Formatter view");
        TextArea input  = styledTextArea("Paste JSON......");
        TextArea output = styledTextArea("");
        output.setEditable(false);

        Button formatBtn  = actionButton(I18n.get("builtin.json.format"),  "#3574F0");
        Button compactBtn = actionButton(I18n.get("builtin.json.compress"), null);
        Button clearBtn   = actionButton("Clear",   null);

        formatBtn.setOnAction(e -> {
            try {
                log.debug("Formatting JSON");
                output.setText(prettyPrint(input.getText().trim()));
            } catch (Exception ex) {
                log.warn("Invalid JSON input: {}", ex.getMessage());
                output.setText("❌ Invalid JSON: " + ex.getMessage());
            }
        });
        compactBtn.setOnAction(e -> {
            log.debug("Compacting JSON");
            output.setText(input.getText().replaceAll("\\s+", ""));
        });
        clearBtn.setOnAction(e -> {
            log.debug("Clearing input and output");
            input.clear();
            output.clear();
        });

        HBox btnRow = new HBox(8, formatBtn, compactBtn, clearBtn);
        btnRow.setPadding(new Insets(0, 0, 12, 0));

        VBox left  = new VBox(6, sectionLabel(I18n.get("builtin.json.input")),  input);
        VBox right = new VBox(6, sectionLabel(I18n.get("builtin.json.output")), output);
        VBox.setVgrow(input,  Priority.ALWAYS);
        VBox.setVgrow(output, Priority.ALWAYS);
        HBox.setHgrow(left,   Priority.ALWAYS);
        HBox.setHgrow(right,  Priority.ALWAYS);

        HBox editors = new HBox(12, left, right);
        VBox.setVgrow(editors, Priority.ALWAYS);

        VBox root = new VBox(12, btnRow, editors);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(root, Priority.ALWAYS);
        return root;
    }

    /**
     * Performs a minimal pretty-print on a JSON string, indenting with two spaces
     * per nesting level and placing newlines after structural characters.
     *
     * @param json the raw JSON string to format
     * @return the formatted JSON string
     */
    private String prettyPrint(String json) {
        StringBuilder sb = new StringBuilder();
        int indent = 0;
        boolean inString = false;
        for (char c : json.toCharArray()) {
            if (c == '"' && (sb.isEmpty() || sb.charAt(sb.length() - 1) != '\\'))
                inString = !inString;
            if (!inString) {
                if (c == '{' || c == '[') {
                    sb.append(c).append('\n');
                    indent += 2;
                    sb.append(" ".repeat(indent));
                    continue;
                } else if (c == '}' || c == ']') {
                    sb.append('\n');
                    indent = Math.max(0, indent - 2);
                    sb.append(" ".repeat(indent)).append(c);
                    continue;
                } else if (c == ',') {
                    sb.append(c).append('\n').append(" ".repeat(indent));
                    continue;
                } else if (c == ':') {
                    sb.append(": ");
                    continue;
                } else if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
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
