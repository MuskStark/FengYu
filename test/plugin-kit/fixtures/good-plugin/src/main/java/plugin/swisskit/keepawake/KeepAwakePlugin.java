package plugin.swisskit.keepawake;

import fan.summer.api.IconStyle;
import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.ToolCategory;
import fan.summer.api.i18n.I18n;
import javafx.scene.Node;
import plugin.swisskit.keepawake.service.KeepAwakeService;
import plugin.swisskit.keepawake.ui.KeepAwakeUi;

public class KeepAwakePlugin implements SwissKitJPlugin {

    private KeepAwakeUi ui;

    @Override
    public String getId() {
        return "plugin.swisskit.keepawake";
    }

    @Override
    public String getName() {
        return I18n.get("plugin.keepawake.name");
    }

    @Override
    public String getDescription() {
        return I18n.get("plugin.keepawake.desc");
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.OTHER;
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getMdiIcon() {
        return "coffee";
    }

    @Override
    public IconStyle getIconStyle() {
        return IconStyle.PURPLE;
    }

    @Override
    public Node createView() {
        I18n.registerPluginBundle("i18n.messages", getClass().getClassLoader());
        ui = new KeepAwakeUi();
        return ui.getView();
    }

    @Override
    public void onActivate() {
        if (ui != null) ui.resumeUi();
    }

    @Override
    public void onDeactivate() {
        if (ui != null) ui.suspendUi();
    }

    @Override
    public boolean hasRunningTasks() {
        return KeepAwakeService.getInstance().isRunning();
    }

    @Override
    public void onBackground() {
        if (ui != null) ui.suspendUi();
    }

    @Override
    public void onForeground() {
        if (ui != null) ui.resumeUi();
    }

    @Override
    public void onUnload() {
        KeepAwakeService.getInstance().stop();
    }
}
