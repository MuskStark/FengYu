package fan.summer.ui.store;

import fan.summer.api.i18n.I18n;
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
        localPage.setManaged(false);

        // ── Sidebar (inline-styled to avoid CSS .sidebar width conflicts) ──
        VBox sidebar = new VBox();
        sidebar.setPrefWidth(180);
        sidebar.setMinWidth(160);
        sidebar.setMaxWidth(200);
        sidebar.setStyle(
            "-fx-background-color: rgba(255,255,255,0.022);" +
            "-fx-border-color: rgba(255,255,255,0.10);" +
            "-fx-border-width: 0 1 0 0;"
        );

        sidebar.getChildren().add(sidebarSectionLabel(I18n.get("store.section")));

        NavItem onlineNav = new NavItem("online", "🌐", I18n.get("store.nav.online"), 0, false);
        NavItem localNav  = new NavItem("local",  "📦", I18n.get("store.nav.local"), 0, false);

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
                for (int j = 0; j < items.length; j++) {
                    items[j].setActive(j == idx);
                    pages[j].setVisible(j == idx);
                    pages[j].setManaged(j == idx);
                }
            });
        }

        // ── Layout ─────────────────────────────────────────
        HBox body = new HBox(sidebar, contentStack);
        HBox.setHgrow(contentStack, Priority.ALWAYS);
        body.setMinWidth(0);

        VBox container = new VBox(body);
        container.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        container.setMinWidth(0);
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
