package fan.summer.buildintool.browser;

import fan.summer.api.IconStyle;
import fan.summer.api.ToolCategory;
import fan.summer.api.ToolType;
import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.ai.AiTool;
import fan.summer.buildintool.browser.ai.BrowserAutomateTool;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * UI-less host plugin for {@link BrowserAutomateTool}.
 *
 * <p>Browser automation has no standalone UI — it's invoked through the AI chat.
 * This plugin exists so the tool has a natural owner in the
 * plugin registry, and so the sidebar shows users that the capability exists.
 * The {@link #createView()} returns a short explanation page.</p>
 */
public class BrowserAutomatePlugin implements SwissKitJPlugin {

    @Override
    public String getId() { return "fan.summer.buildin.browser-automate"; }

    @Override
    public String getName() { return "Browser Automate"; }

    @Override
    public String getDescription() {
        return "Provides browser automation capability to the AI chat. "
             + "Invoke by asking the AI to perform web tasks.";
    }

    @Override
    public ToolCategory getCategory() { return ToolCategory.DEV; }

    @Override
    public String getVersion() { return "1.0.0"; }

    @Override
    public String getMdiIcon() { return "web"; }

    @Override
    public IconStyle getIconStyle() { return IconStyle.TEAL; }

    @Override
    public ToolType getType() { return ToolType.BUILTIN; }

    @Override
    public Node createView() {
        VBox box = new VBox(8);
        box.setStyle("-fx-padding: 24;");
        Label title = new Label("Browser Automation");
        title.setStyle("-fx-font-size: 16; -fx-font-weight: bold;");
        Label body = new Label(
            "This plugin provides browser automation to the AI chat. "
          + "Open the AI chat and describe what you want, e.g. "
          + "\"Open github.com and search for 'playwright java'\"."
        );
        body.setWrapText(true);
        body.setMaxWidth(Double.MAX_VALUE);
        box.getChildren().addAll(title, body);
        return box;
    }

    @Override
    public List<AiTool> aiTools() {
        return List.of(new BrowserAutomateTool());
    }
}
