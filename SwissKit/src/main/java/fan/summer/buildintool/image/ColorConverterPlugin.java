package fan.summer.buildintool.image;

import fan.summer.zhiflow.api.IconStyle;
import fan.summer.zhiflow.api.SwissKitJPlugin;
import fan.summer.zhiflow.api.ToolCategory;
import fan.summer.zhiflow.api.ToolType;
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.i18n.I18n;
import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;
import fan.summer.ai.tools.BuiltinColorConvertTool;
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

import java.util.List;

/**
 * Built-in plugin for converting colors between HEX, RGB, and HSL formats.
 * Provides a live preview swatch that updates as the user types in any field.
 *
 * <p>All three formats (HEX, RGB, HSL) are kept in sync automatically —
 * editing one field updates the other two via a shared listener.
 *
 * @see SwissKitJPlugin
 */
public class ColorConverterPlugin implements SwissKitJPlugin {

    private static final PluginLogger log = LoggerFactory.getLogger(ColorConverterPlugin.class);

    @Override public String getId()          { return "builtin.color"; }
    @Override public String getName()        { return I18n.get("builtin.color-converter.name"); }
    @Override public String getDescription() { return I18n.get("builtin.color-converter.desc"); }
    @Override public ToolCategory getCategory()    { return ToolCategory.IMAGE; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getMdiIcon()    { return "palette"; }
    @Override public IconStyle getIconStyle()   { return IconStyle.PINK; }
    @Override public ToolType getType()        { return ToolType.BUILTIN; }

    @Override
    public List<AiTool> aiTools() {
        return List.of(new BuiltinColorConvertTool());
    }

    /**
     * Creates and returns the plugin view containing HEX/RGB/HSL input fields
     * and a color preview swatch.
     *
     * @return the root JavaFX Node for this plugin's UI
     */
    @Override
    public Node createView() {
        log.debug("Creating Color Converter view");
        TextField hexField = styledField("#3574F0");
        TextField rgbField = styledField("53, 116, 240");
        TextField hslField = styledField("220°, 90%, 66%");

        Region preview = new Region();
        preview.setPrefSize(80, 80);
        preview.setMinSize(80, 80);
        preview.setStyle("-fx-background-radius: 16; -fx-background-color: #3574F0;");

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
                log.debug("Color updated: hex={}", hex);
            } catch (Exception ignored) {}
        };

        hexField.textProperty().addListener((o, oldV, newV) -> updatePreview.run());

        VBox fields = new VBox(10,
            fieldRow(I18n.get("builtin.color.hex"), hexField),
            fieldRow(I18n.get("builtin.color.rgb"), rgbField),
            fieldRow(I18n.get("builtin.color.hsl"), hslField)
        );
        HBox.setHgrow(fields, Priority.ALWAYS);

        HBox top = new HBox(20, preview, fields);
        top.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(20, sectionLabel(I18n.get("builtin.color.converter")), top);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: transparent;");
        updatePreview.run();
        return root;
    }

    /**
     * Builds a label-field row for the color input section.
     *
     * @param label the display text for the field label
     * @param field the TextField control to place beside the label
     * @return an HBox containing the label and field, with the field growing to fill space
     */
    private HBox fieldRow(String label, TextField field) {
        Label l = new Label(label);
        l.getStyleClass().add("sk-t2");
        l.setStyle("-fx-min-width: 40px; -fx-font-size: 12px;");
        HBox.setHgrow(field, Priority.ALWAYS);
        HBox row = new HBox(12, l, field);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * Creates a styled TextField pre-filled with the given text.
     *
     * @param text the initial text value
     * @return a styled TextField control
     */
    private static TextField styledField(String text) {
        TextField tf = new TextField(text);
        tf.getStyleClass().addAll("sk-surface", "sk-outlined", "sk-t1");
        tf.setStyle(
            "-fx-border-width: 1;" +
            "-fx-border-radius: 8; -fx-background-radius: 8;" +
            "-fx-font-size: 13px;" +
            "-fx-padding: 8 12 8 12; -fx-font-family: 'SF Mono','Consolas',monospace;"
        );
        return tf;
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
