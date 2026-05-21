package fan.summer.ui.sidebar;

import javafx.animation.Interpolator;
import javafx.animation.ScaleTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Left navigation sidebar with scrollable content.
 * Listen for category switch events via setOnCategorySelect.
 */
public class Sidebar extends VBox {

    public record Category(String id, String icon, String label, int count, boolean isNew) {}

    private final VBox content = new VBox();
    private final List<NavItem> navItems = new ArrayList<>();
    private NavItem activeItem;
    private Consumer<String> onCategorySelect;
    private Runnable onSettingsSelect;

    public Sidebar() {
        getStyleClass().add("sidebar");
        setPrefWidth(220);
        setMinWidth(200);
        setMaxWidth(260);
        setSpacing(0);
        build();
    }

    public void setOnCategorySelect(Consumer<String> handler) {
        this.onCategorySelect = handler;
    }

    public void setOnSettingsSelect(Runnable handler) {
        this.onSettingsSelect = handler;
    }

    /** Dynamically update plugin category badge numbers */
    public void updateBadge(String categoryId, int count) {
        navItems.stream()
            .filter(item -> item.getCategoryId().equals(categoryId))
            .findFirst()
            .ifPresent(item -> item.setBadge(count));
    }

    // ── Build static navigation structure ──────────────────────────────────

    private void build() {
        content.setSpacing(0);

        // ── AI section (first position) ────────────────────────────────
        content.getChildren().add(sectionLabel("AI ASSISTANT"));
        addNavItem("ai", "🤖", "AI Chat", 0, true);

        content.getChildren().add(divider());

        // ── Tools section ────────────────────────────────────
        content.getChildren().add(sectionLabel("TOOLS"));
        addNavItem("all",     "⊞", "All Tools",        0, false);
        addNavItem("text",    "✏️", "Text Processing",  0, false);
        addNavItem("image",   "🖼", "Image Processing", 0, false);
        addNavItem("dev",     "⌨️", "Developer Tools",  0, false);
        addNavItem("net",     "📡", "Network Tools",    0, false);
        addNavItem("other",   "📦", "Other Tools",      0, false);

        content.getChildren().add(divider());

        // ── Plugins section ────────────────────────────────────
        content.getChildren().add(sectionLabel("PLUGINS"));
        addNavItem("plugins", "🧩", "Installed Plugins", 0, true);
        addNavItem("store",   "🏪", "Plugin Store",   0, false);

        content.getChildren().add(divider());

        // ── Favorites section ────────────────────────────────────
        content.getChildren().add(sectionLabel("FAVORITES"));
        addNavItem("fav", "⭐", "My Favorites", 0, false);

        content.getChildren().add(divider());

        // ── Settings (always at bottom) ──────────────────────────
        addSettingsItem("⚙️", "Settings");

        // ── Wrap in ScrollPane ────────────────────────────────────
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        scrollPane.getStyleClass().add("sidebar-scroll");

        getChildren().add(scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        // Activate "AI Chat" by default (first item)
        if (!navItems.isEmpty()) {
            activate(navItems.get(0), false);
        }
    }

    private void addNavItem(String id, String icon, String label, int count, boolean isNew) {
        NavItem item = new NavItem(id, icon, label, count, isNew);
        item.setOnMouseClicked(e -> activate(item, true));
        navItems.add(item);
        content.getChildren().add(item);
    }

    private void addSettingsItem(String icon, String label) {
        NavItem item = new NavItem("settings", icon, label, 0, false);
        item.setOnMouseClicked(e -> {
            if (onSettingsSelect != null) onSettingsSelect.run();
        });
        content.getChildren().add(item);
    }

    private void activate(NavItem item, boolean fireEvent) {
        if (activeItem != null) activeItem.setActive(false);
        activeItem = item;
        item.setActive(true);
        if (fireEvent && onCategorySelect != null) {
            onCategorySelect.accept(item.getCategoryId());
        }
    }

    // ── Helper node factory ──────────────────────────────────────

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
    // Inner class: single navigation item
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
            iconLabel.getStyleClass().add("nav-item-icon");

            Label textLabel = new Label(label);
            textLabel.getStyleClass().add("nav-item-text");
            HBox.setHgrow(textLabel, Priority.ALWAYS);

            badgeLabel = new Label(count > 0 ? String.valueOf(count) : "");
            badgeLabel.getStyleClass().add("nav-badge");
            if (isNew) badgeLabel.getStyleClass().add("nav-badge-new");
            badgeLabel.setVisible(count > 0);

            getChildren().addAll(iconLabel, textLabel, badgeLabel);
            setCursor(javafx.scene.Cursor.HAND);
        }

        public String getCategoryId() { return categoryId; }

        public void setActive(boolean active) {
            this.active = active;
            if (active) {
                getStyleClass().add("active");
                ScaleTransition st = new ScaleTransition(Duration.millis(160), this);
                st.setFromX(0.97); st.setFromY(0.97);
                st.setToX(1.0); st.setToY(1.0);
                st.setInterpolator(Interpolator.SPLINE(0.34, 0.9, 0.64, 1.0));
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
