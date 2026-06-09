package fan.summer.ui.store;

import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.i18n.I18n;
import fan.summer.ui.sidebar.Sidebar.NavItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import java.util.HashMap;
import java.util.Map;

/**
 * Plugin Store UI container that combines Online Store and Local Install panes
 * in a sidebar-content layout. The sidebar allows switching between the two modes.
 * <p>
 * The view is built lazily on first access and cached for the lifetime of the session.
 *
 * @see OnlineStorePane
 * @see LocalInstallPane
 * @since 1.0
 */
public class PluginStoreUi {

    private static final Logger LOG = LoggerFactory.getLogger(PluginStoreUi.class);

    /**
     * Builds the plugin store UI containing the sidebar
     * and both the online store and local install content panes.
     * A fresh view is created each time so locale changes are reflected.
     *
     * @return the root Node of the plugin store UI
     */
    public static Node build(ObservableList<SwissKitJPlugin> installed) {

        // ── Content pages ──────────────────────────────────
        Map<String, String> installedVersions = new HashMap<>();
        if (installed != null) {
            for (SwissKitJPlugin p : installed) {
                installedVersions.put(p.getId(), p.getVersion());
            }
        }
        Node onlinePage = new OnlineStorePane(null, installedVersions);
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
                LOG.info("PluginStore nav switched to index: {}", idx);
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

        LOG.debug("PluginStoreUi view built");
        return container;
    }

    /**
     * Creates a section label styled as uppercase text for use in the sidebar.
     *
     * @param text the label text to display (will be uppercased)
     * @return a styled Label node
     */
    private static Label sidebarSectionLabel(String text) {
        Label l = new Label(text.toUpperCase());
        l.getStyleClass().add("sidebar-section-label");
        return l;
    }
}
