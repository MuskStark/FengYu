package fan.summer.buildintool.image;

import fan.summer.api.SwissKitJPlugin;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

public class ColorConverterPlugin implements SwissKitJPlugin {

    @Override public String getId()          { return "builtin.color"; }
    @Override public String getName()        { return "Color Converter"; }
    @Override public String getDescription() { return "HEX / RGB / HSL conversion with live preview"; }
    @Override public String getCategory()    { return "image"; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getMdiIcon()    { return "palette"; }
    @Override public String getIconStyle()   { return "ic-pink"; }
    @Override public String getType()        { return "builtin"; }

    @Override
    public Node createView() {
        TextField hexField = styledField("#5b8cf7");
        TextField rgbField = styledField("91, 140, 247");
        TextField hslField = styledField("220°, 90%, 66%");

        Region preview = new Region();
        preview.setPrefSize(80, 80);
        preview.setMinSize(80, 80);
        preview.setStyle("-fx-background-radius: 16; -fx-background-color: #5b8cf7;");

        Runnable updatePreview = () -> {
            try {
                String hex = hexField.getText().trim();
                if (!hex.startsWith("#")) hex = "#" + hex;
                preview.setStyle("-fx-background-radius: 16; -fx-background-color: " + hex + ";");
                Color c = Color.web(hex);
                rgbField.setText(String.format("%.0f, %.0f, %.0f",
                    c.getRed() * 255, c.getGreen() * 255, c.getBlue() * 255));
                hslField.setText(String.format("%.0f°, %.0f%%, %.0f%%",
                    c.getHue(), c.getSaturation() * 100, c.getBrightness() * 100));
            } catch (Exception ignored) {}
        };

        hexField.textProperty().addListener((o, oldV, newV) -> updatePreview.run());

        VBox fields = new VBox(10,
            fieldRow("HEX", hexField),
            fieldRow("RGB", rgbField),
            fieldRow("HSL", hslField)
        );
        HBox.setHgrow(fields, Priority.ALWAYS);

        HBox top = new HBox(20, preview, fields);
        top.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(20, sectionLabel("Color Converter"), top);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: transparent;");
        updatePreview.run();
        return root;
    }

    private HBox fieldRow(String label, TextField field) {
        Label l = new Label(label);
        l.setStyle("-fx-text-fill: rgba(255,255,255,0.40); -fx-min-width: 40px; -fx-font-size: 12px;");
        HBox.setHgrow(field, Priority.ALWAYS);
        HBox row = new HBox(12, l, field);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static TextField styledField(String text) {
        TextField tf = new TextField(text);
        tf.setStyle(
            "-fx-background-color: rgba(255,255,255,0.06);" +
            "-fx-border-color: rgba(255,255,255,0.12); -fx-border-width: 1;" +
            "-fx-border-radius: 8; -fx-background-radius: 8;" +
            "-fx-text-fill: rgba(255,255,255,0.88); -fx-font-size: 13px;" +
            "-fx-padding: 8 12 8 12; -fx-font-family: 'SF Mono','Consolas',monospace;"
        );
        return tf;
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
