package fan.summer;

import javafx.animation.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
 /* Left sidebar navigation.
 * Listens for category switch events via setOnCategorySelect.
 */
public class Sidebar extends VBox {

    public record Category(String id, String icon, String label, int count, boolean isNew) {}

    private final List<NavItem> navItems = new ArrayList<>();
    private NavItem activeItem;
    private Consumer<String> onCategorySelect;

    public Sidebar() {
        getStyleClass().add("sidebar");
        setPrefWidth(220);
        setMinWidth(200);
        setMaxWidth(260);
        build();
    }

    public void setOnCategorySelect(Consumer<String> handler) {
        this.onCategorySelect = handler;
    }

    /** Dynamically update badge count for plugin category */
    public void updateBadge(String categoryId, int count) {
        navItems.stream()
            .filter(item -> item.getCategoryId().equals(categoryId))
            .findFirst()
            .ifPresent(item -> item.setBadge(count));
    }

    // ── Build Static Navigation Structure ──────────────────────────────────

    private void build() {
        setSpacing(0);

        // ── Tools Section ────────────────────────────────────
        getChildren().add(sectionLabel("TOOLS"));
        addNavItem("all",     "⊞", "All Tools", 12, false);
        addNavItem("text",    "✏️", "Text Processing",  4, false);
        addNavItem("image",   "🖼", "Image Processing",  3, false);
        addNavItem("dev",     "⌨️", "Developer Tools",  3, false);
        addNavItem("net",     "📡", "Network Tools",  2, false);

        getChildren().add(divider());

        // ── Plugins Section ────────────────────────────────────
        getChildren().add(sectionLabel("PLUGINS"));
        addNavItem("plugins", "🧩", "Installed Plugins", 3, true);
        addNavItem("store",   "🏪", "Plugin Store",   0, false);

        getChildren().add(divider());

        // ── Favorites Section ────────────────────────────────────
        getChildren().add(sectionLabel("FAVORITES"));
        addNavItem("fav", "⭐", "My Favorites", 5, false);

        // ── Bottom Spring + Settings ──────────────────────────────
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        getChildren().add(spacer);
        getChildren().add(divider());
        addNavItem("settings", "⚙️", "Settings", 0, false);

        // Default activate "All Tools"
        if (!navItems.isEmpty()) {
            activate(navItems.get(0), false);
        }
    }

    private void addNavItem(String id, String icon, String label, int count, boolean isNew) {
        NavItem item = new NavItem(id, icon, label, count, isNew);
        item.setOnMouseClicked(e -> activate(item, true));
        navItems.add(item);
        getChildren().add(item);
    }

    private void activate(NavItem item, boolean fireEvent) {
        if (activeItem != null) activeItem.setActive(false);
        activeItem = item;
        item.setActive(true);
        if (fireEvent && onCategorySelect != null) {
            onCategorySelect.accept(item.getCategoryId());
        }
    }

    // ── Helper Node Factory ──────────────────────────────────────

    private Label sectionLabel(String text) {
        Label l = new Label(text.toUpperCase());
        l.getStyleClass().add("sidebar-section-label");
        return l;
    }

    private Region divider() {
        Region d = new Region();
        d.getStyleClass().add("sidebar-divider");
        d.setPrefHeight(1);
        VBox.setMargin(d, new Insets(6, 4, 6, 4));
        return d;
    }

    // ════════════════════════════════════════════════════
    // Inner class: Single navigation item
    // ════════════════════════════════════════════════════

    public static class NavItem extends HBox {

        private final String categoryId;
        private final Label  badgeLabel;
        private boolean active = false;

        public NavItem(String id, String icon, String label, int count, boolean isNew) {
            this.categoryId = id;

            getStyleClass().add("nav-item");
            setAlignment(Pos.CENTER_LEFT);
            setSpacing(10);
            setPrefHeight(34);

            Label iconLabel = new Label(icon);
            iconLabel.setStyle("-fx-font-size: 14px; -fx-min-width: 18px; -fx-alignment: center;");

            Label textLabel = new Label(label);
            textLabel.setStyle("-fx-font-size: 13px;");
            HBox.setHgrow(textLabel, Priority.ALWAYS);

            badgeLabel = new Label(count > 0 ? String.valueOf(count) : "");
            badgeLabel.getStyleClass().add(isNew ? "nav-badge nav-badge-new" : "nav-badge");
            badgeLabel.setVisible(count > 0);

            getChildren().addAll(iconLabel, textLabel, badgeLabel);
            setCursor(javafx.scene.Cursor.HAND);
        }

        public String getCategoryId() { return categoryId; }

        public void setActive(boolean active) {
            this.active = active;
            if (active) {
                getStyleClass().add("active");
                // Elastic scale feedback
                ScaleTransition st = new ScaleTransition(Duration.millis(160), this);
                st.setFromX(0.97); st.setFromY(0.97);
                st.setToX(1.0); st.setToY(1.0);
                st.setInterpolator(Interpolator.SPLINE(0.34, 1.4, 0.64, 1));
                st.play();
            } else {
                getStyleClass().remove("active");
            }
        }

        public void setBadge(int count) {
            badgeLabel.setText(count > 0 ? String.valueOf(count) : "");
            badgeLabel.setVisible(count > 0);
        }

        public boolean isActive() { return active; }
    }
}