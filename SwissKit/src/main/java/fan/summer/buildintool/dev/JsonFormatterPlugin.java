package fan.summer.buildintool.dev;

import fan.summer.api.IconStyle;
import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.ToolCategory;
import fan.summer.api.ToolType;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public class JsonFormatterPlugin implements SwissKitJPlugin {

    @Override public String getId()          { return "builtin.json-formatter"; }
    @Override public String getName()        { return "JSON Formatter"; }
    @Override public String getDescription() { return "Format, compress, and validate JSON data"; }
    @Override public ToolCategory getCategory()    { return ToolCategory.DEV; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getMdiIcon()    { return "code-json"; }
    @Override public IconStyle getIconStyle()   { return IconStyle.BLUE; }
    @Override public ToolType getType()        { return ToolType.BUILTIN; }

    @Override
    public Node createView() {
        TextArea input  = styledTextArea("Paste JSON......");
        TextArea output = styledTextArea("");
        output.setEditable(false);

        Button formatBtn  = actionButton("Format",  "#5b8cf7");
        Button compactBtn = actionButton("Compact", "rgba(255,255,255,0.12)");
        Button clearBtn   = actionButton("Clear",   "rgba(255,255,255,0.08)");

        formatBtn.setOnAction(e -> {
            try {
                output.setText(prettyPrint(input.getText().trim()));
            } catch (Exception ex) {
                output.setText("❌ Invalid JSON: " + ex.getMessage());
            }
        });
        compactBtn.setOnAction(e -> output.setText(input.getText().replaceAll("\\s+", "")));
        clearBtn.setOnAction(e -> { input.clear(); output.clear(); });

        HBox btnRow = new HBox(8, formatBtn, compactBtn, clearBtn);
        btnRow.setPadding(new Insets(0, 0, 12, 0));

        VBox left  = new VBox(6, sectionLabel("Input"),  input);
        VBox right = new VBox(6, sectionLabel("Output"), output);
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

    private static TextArea styledTextArea(String prompt) {
        TextArea ta = new TextArea();
        ta.setPromptText(prompt);
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

    private static Button actionButton(String text, String bg) {
        Button btn = new Button(text);
        btn.setStyle(
            "-fx-background-color: " + bg + ";" +
            "-fx-text-fill: rgba(255,255,255,0.88); -fx-font-size: 13px;" +
            "-fx-background-radius: 8; -fx-border-width: 0;" +
            "-fx-padding: 8 18 8 18; -fx-cursor: hand;"
        );
        return btn;
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
