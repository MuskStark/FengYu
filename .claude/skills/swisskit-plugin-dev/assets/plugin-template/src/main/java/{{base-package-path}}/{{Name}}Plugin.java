package {{base-package}};

import fan.summer.api.IconStyle;
import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.ToolCategory;
import fan.summer.api.ToolType;
import fan.summer.api.ai.AiTool;
import fan.summer.api.i18n.I18n;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

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
 * {{Name}} plugin for SwissKitJ.
 *
 * <p>Implements {@link SwissKitJPlugin} directly in a single class (the recommended pattern).
 * The host caches the node returned by {@link #createView()} and reuses it, so build the UI
 * once and store control references in fields.
 *
 * @see SwissKitJPlugin
 */
public class {{Name}}Plugin implements SwissKitJPlugin {

    private static final PluginLogger log = LoggerFactory.getLogger({{Name}}Plugin.class);

    /** i18n key prefix — keys live in i18n/messages[_zh].properties under this prefix. */
    private static final String P = "plugin.{{slug}}.";

    // Cached UI controls (createView runs once; later activations reuse them).
    private TextArea inputArea;
    private TextArea outputArea;
    private VBox root;

    // ── Metadata ──────────────────────────────────────────────────────────

    @Override public String getId()          { return "{{base-package}}"; }
    @Override public String getName()        { return I18n.get(P + "name"); }
    @Override public String getDescription() { return I18n.get(P + "desc"); }
    @Override public ToolCategory getCategory() { return ToolCategory.OTHER; }  // DEV/TEXT/IMAGE/NET/OTHER
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getMdiIcon()     { return "star-outline"; }   // bare MDI name, NO mdi- prefix
    @Override public IconStyle getIconStyle() { return IconStyle.BLUE; }  // default; pick from BLUE/PURPLE/TEAL/AMBER/RED/PINK/GRAY
    // getType() defaults to ToolType.PLUGIN — correct for an external plugin.

    // ── View (built once, cached) ──────────────────────────────────────────

    @Override
    public Node createView() {
        log.debug("Building {{Name}} view");
        // Register the i18n bundle with THIS plugin's ClassLoader before any I18n.get/bind.
        // (Required for the dev profile to resolve keys standalone.)
        I18n.registerPluginBundle("i18n.messages", getClass().getClassLoader());

        inputArea = new TextArea();
        inputArea.setPromptText(I18n.get(P + "input.prompt"));
        inputArea.getStyleClass().add("sk-field");

        outputArea = new TextArea();
        outputArea.setEditable(false);
        outputArea.setPromptText(I18n.get(P + "output.prompt"));
        outputArea.getStyleClass().add("sk-field");

        Button runBtn = new Button(I18n.get(P + "action.run"));
        runBtn.getStyleClass().add("sk-btn-primary");
        runBtn.setOnAction(e -> {
            try {
                outputArea.setText(process(inputArea.getText()));
            } catch (Exception ex) {
                log.warn("Run failed: {}", ex.getMessage());
                outputArea.setText("❌ " + ex.getMessage());
            }
        });

        Button clearBtn = new Button(I18n.get(P + "action.clear"));
        clearBtn.getStyleClass().add("sk-btn-secondary");
        clearBtn.setOnAction(e -> { inputArea.clear(); outputArea.clear(); });

        HBox buttonRow = new HBox(8, runBtn, clearBtn);

        Label inLabel  = sectionLabel(I18n.get(P + "input"));
        Label outLabel = sectionLabel(I18n.get(P + "output"));
        VBox left  = new VBox(6, inLabel,  inputArea);
        VBox right = new VBox(6, outLabel, outputArea);

        // Fill remaining space — the two-call pattern (see references/ui-and-tokens.md §layout pitfalls):
        VBox.setVgrow(inputArea,  Priority.ALWAYS);
        VBox.setVgrow(outputArea, Priority.ALWAYS);
        HBox.setHgrow(left,  Priority.ALWAYS);
        left.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(right, Priority.ALWAYS);
        right.setMaxWidth(Double.MAX_VALUE);

        HBox editors = new HBox(12, left, right);
        VBox.setVgrow(editors, Priority.ALWAYS);

        root = new VBox(12, buttonRow, editors);
        root.setPadding(new Insets(20));
        VBox.setVgrow(root, Priority.ALWAYS);
        return root;
    }

    /** Small muted field label. */
    private Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sk-t2");   // → -sk-text-secondary
        return l;
    }

    /** TODO: replace with the plugin's real logic. */
    private String process(String input) {
        return input;   // echo for the scaffold
    }

    // ── Lifecycle hooks (override as needed) ───────────────────────────────

    @Override public void onActivate()   { log.debug("{{Name}} activated"); }
    @Override public void onDeactivate() { log.debug("{{Name}} deactivated"); }
    @Override public void onUnload()     { log.debug("{{Name}} unloaded — release resources here"); }

    // If you do background work, override hasRunningTasks() to return true while it runs,
    // so the host keeps this view cached instead of deactivating on back-navigation.

    // ── AI tools (optional) ────────────────────────────────────────────────

    @Override
    public List<AiTool> aiTools() {
        return List.of();   // return List.of(new YourAiTool()) to expose tools to the AI chat
    }
}
