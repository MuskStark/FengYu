package fan.summer.zhiflow.buildintool.browser;

import fan.summer.zhiflow.api.IconStyle;
import fan.summer.zhiflow.api.ToolCategory;
import fan.summer.zhiflow.api.ToolType;
import fan.summer.zhiflow.api.SwissKitJPlugin;
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.i18n.I18n;
import fan.summer.zhiflow.buildintool.browser.ai.BrowserAutomateTool;
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
    public String getId() { return "fan.summer.zhiflow.buildin.browser-automate"; }

    @Override
    public String getName() { return I18n.get("builtin.browser-automate.name"); }

    @Override
    public String getDescription() { return I18n.get("builtin.browser-automate.desc"); }

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
        Label title = new Label(I18n.get("builtin.browser-automate.name"));
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
