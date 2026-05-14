package fan.summer.buildintool.dev;

import fan.summer.api.SwissKitJPlugin;
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

public class HashCalculatorPlugin implements SwissKitJPlugin {

    private static final String[][] ALGOS = {
        {"MD5", "MD5"}, {"SHA-1", "SHA-1"}, {"SHA-256", "SHA-256"}, {"SHA-512", "SHA-512"}
    };

    @Override public String getId()          { return "builtin.hash"; }
    @Override public String getName()        { return "Hash Calculator"; }
    @Override public String getDescription() { return "MD5 / SHA-1 / SHA-256 / SHA-512"; }
    @Override public String getCategory()    { return "dev"; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getIconText()    { return "#"; }
    @Override public String getIconStyle()   { return "ic-amber"; }
    @Override public String getType()        { return "builtin"; }

    @Override
    public Node createView() {
        TextArea input = styledTextArea("Input text...");
        VBox results   = new VBox(8);

        Button calcBtn = actionButton("Calculate Hash", "#5b8cf7");
        calcBtn.setOnAction(e -> {
            results.getChildren().clear();
            for (String[] algo : ALGOS) {
                try {
                    MessageDigest md   = MessageDigest.getInstance(algo[1]);
                    byte[]        hash = md.digest(input.getText().getBytes(StandardCharsets.UTF_8));
                    StringBuilder hex  = new StringBuilder();
                    for (byte b : hash) hex.append(String.format("%02x", b));
                    results.getChildren().add(hashRow(algo[0], hex.toString()));
                } catch (Exception ex) {
                    results.getChildren().add(hashRow(algo[0], "Error"));
                }
            }
        });

        VBox root = new VBox(12, sectionLabel("Input Text"), input, calcBtn, sectionLabel("Result"), results);
        VBox.setVgrow(input, Priority.ALWAYS);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: transparent;");
        return root;
    }

    private HBox hashRow(String algo, String value) {
        Label algoLabel = new Label(algo);
        algoLabel.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.45); -fx-font-size: 11px;" +
            "-fx-min-width: 60px; -fx-font-family: 'SF Mono','Consolas',monospace;"
        );
        Label valueLabel = new Label(value);
        valueLabel.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.85); -fx-font-size: 11px;" +
            "-fx-font-family: 'SF Mono','Consolas',monospace;"
        );
        valueLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(valueLabel, Priority.ALWAYS);

        Button copy = new Button("Copy");
        copy.setStyle(
            "-fx-background-color: rgba(255,255,255,0.08); -fx-border-width: 0;" +
            "-fx-text-fill: rgba(255,255,255,0.5); -fx-font-size: 10px;" +
            "-fx-background-radius: 4; -fx-cursor: hand; -fx-padding: 2 8 2 8;"
        );
        copy.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(value);
            Clipboard.getSystemClipboard().setContent(cc);
            copy.setText("✓");
            PauseTransition pt = new PauseTransition(Duration.seconds(1.5));
            pt.setOnFinished(ev -> copy.setText("Copy"));
            pt.play();
        });

        HBox row = new HBox(10, algoLabel, valueLabel, copy);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle(
            "-fx-background-color: rgba(255,255,255,0.04);" +
            "-fx-background-radius: 8; -fx-padding: 10 12 10 12;"
        );
        return row;
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
