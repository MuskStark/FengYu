package fan.summer.buildintool.dev;

import fan.summer.api.SwissKitJPlugin;
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

public class Base64Plugin implements SwissKitJPlugin {

    @Override public String getId()          { return "builtin.base64"; }
    @Override public String getName()        { return "Base64"; }
    @Override public String getDescription() { return "Base64 Encode / Decode"; }
    @Override public String getCategory()    { return "dev"; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getIconText()    { return "64"; }
    @Override public String getIconStyle()   { return "ic-teal"; }
    @Override public String getType()        { return "builtin"; }

    @Override
    public Node createView() {
        TextArea input  = styledTextArea("Input text...");
        TextArea output = styledTextArea("");
        output.setEditable(false);

        Button encodeBtn = actionButton("Encode →",  "#5b8cf7");
        Button decodeBtn = actionButton("← Decode",  "rgba(255,255,255,0.12)");
        Button swapBtn   = actionButton("↕ Swap",    "rgba(255,255,255,0.08)");

        encodeBtn.setOnAction(e -> {
            try {
                byte[] encoded = Base64.getEncoder().encode(input.getText().getBytes(StandardCharsets.UTF_8));
                output.setText(new String(encoded));
            } catch (Exception ex) {
                output.setText("Error: " + ex.getMessage());
            }
        });
        decodeBtn.setOnAction(e -> {
            try {
                byte[] decoded = Base64.getDecoder().decode(input.getText().trim());
                output.setText(new String(decoded, StandardCharsets.UTF_8));
            } catch (Exception ex) {
                output.setText("❌ Invalid Base64");
            }
        });
        swapBtn.setOnAction(e -> {
            String tmp = input.getText();
            input.setText(output.getText());
            output.setText(tmp);
        });

        HBox btnRow = new HBox(8, encodeBtn, decodeBtn, swapBtn);
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
        return root;
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
