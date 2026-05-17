package fan.summer.ui.store;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Plugin Store UI — combines Online Store and Local Install tabs.
 * Shown when user clicks the "Plugin Store" sidebar item.
 */
public class PluginStoreUi {

    private static Node view;

    public static Node build() {
        if (view != null) return view;

        VBox container = new VBox();
        container.setStyle(
            "-fx-background-color: transparent;" +
            "-fx-padding: 0;"
        );

        // Tab pane for Online / Local tabs
        TabPane tabs = new TabPane();
        tabs.setStyle("-fx-background-color: transparent; -fx-pref-height: 56;");

        Tab onlineTab = new Tab("Online Store");
        onlineTab.setContent(new OnlineStorePane(null));
        onlineTab.setStyle(
            "-fx-background-color: transparent;" +
            "-fx-padding: 12 28 12 28;" +
            "-fx-text-fill: rgba(255,255,255,0.55);" +
            "-fx-font-size: 13px; -fx-font-weight: 500;"
        );

        Tab localTab = new Tab("Local Install");
        localTab.setContent(new LocalInstallPane(null));
        localTab.setStyle(
            "-fx-background-color: transparent;" +
            "-fx-padding: 12 28 12 28;" +
            "-fx-text-fill: rgba(255,255,255,0.55);" +
            "-fx-font-size: 13px; -fx-font-weight: 500;"
        );

        // Style active tab
        tabs.getTabs().addAll(onlineTab, localTab);

        for (int i = 0; i < tabs.getTabs().size(); i++) {
            Tab t = tabs.getTabs().get(i);
            int index = i;
            String baseStyle = t.getStyle();
            t.selectedProperty().addListener((obs, old, selected) -> {
                if (selected) {
                    t.setStyle(
                        "-fx-background-color: rgba(91,140,247,0.12);" +
                        "-fx-border-color: #5b8cf7;" +
                        "-fx-border-width: 0 0 2 0;" +
                        "-fx-text-fill: #5b8cf7;" +
                        "-fx-font-size: 13px; -fx-font-weight: 500;" +
                        "-fx-padding: 12 28 12 28;"
                    );
                } else {
                    t.setStyle(
                        "-fx-background-color: transparent;" +
                        "-fx-border-color: transparent;" +
                        "-fx-border-width: 0 0 2 0;" +
                        "-fx-text-fill: rgba(255,255,255,0.55);" +
                        "-fx-font-size: 13px; -fx-font-weight: 500;" +
                        "-fx-padding: 12 28 12 28;"
                    );
                }
            });
            // Set initial state
            if (index == 0) {
                t.setStyle(
                    "-fx-background-color: rgba(91,140,247,0.12);" +
                    "-fx-border-color: #5b8cf7;" +
                    "-fx-border-width: 0 0 2 0;" +
                    "-fx-text-fill: #5b8cf7;" +
                    "-fx-font-size: 13px; -fx-font-weight: 500;" +
                    "-fx-padding: 12 28 12 28;"
                );
            }
        }

        container.getChildren().add(tabs);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        view = container;
        return view;
    }
}