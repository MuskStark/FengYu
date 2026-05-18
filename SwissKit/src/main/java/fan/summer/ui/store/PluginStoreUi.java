package fan.summer.ui.store;

import fan.summer.ui.sidebar.Sidebar.NavItem;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Plugin Store UI — sidebar menu with Online Store and Local Install sections.
 */
public class PluginStoreUi {

    private static Node view;

    public static Node build() {
        if (view != null) return view;

        // ── Content pages ──────────────────────────────────
        Node onlinePage = new OnlineStorePane(null);
        Node localPage  = new LocalInstallPane(null);

        StackPane contentStack = new StackPane(onlinePage, localPage);
        contentStack.setStyle("-fx-background-color: transparent;");
        localPage.setVisible(false);

        // ── Sidebar ────────────────────────────────────────
        VBox sidebar = new VBox();
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(180);
        sidebar.setMinWidth(160);
        sidebar.setMaxWidth(200);

        sidebar.getChildren().add(sidebarSectionLabel("STORE"));

        NavItem onlineNav = new NavItem("online", "🌐", "Online Store", 0, false);
        NavItem localNav  = new NavItem("local",  "📦", "Local Install", 0, false);

        onlineNav.setActive(true);
        sidebar.getChildren().addAll(onlineNav, localNav);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        sidebar.getChildren().add(spacer);

        // ── Selection wiring ───────────────────────────────
        NavItem[] items = {onlineNav, localNav};
        Node[]    pages = {onlinePage, localPage};

        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            items[i].setOnMouseClicked(e -> {
                for (NavItem ni : items) ni.setActive(false);
                for (Node p : pages) p.setVisible(false);
                items[idx].setActive(true);
                pages[idx].setVisible(true);
            });
        }

        // ── Layout ─────────────────────────────────────────
        HBox body = new HBox(sidebar, contentStack);
        HBox.setHgrow(contentStack, Priority.ALWAYS);

        VBox container = new VBox(body);
        container.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        VBox.setVgrow(body, Priority.ALWAYS);

        view = container;
        return view;
    }

    private static Label sidebarSectionLabel(String text) {
        Label l = new Label(text.toUpperCase());
        l.getStyleClass().add("sidebar-section-label");
        return l;
    }
}