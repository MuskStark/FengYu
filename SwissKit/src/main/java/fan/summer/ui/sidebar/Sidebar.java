package fan.summer.ui.sidebar;

import fan.summer.api.MdiIconUtil;
import fan.summer.api.i18n.I18n;
import fan.summer.api.theme.ThemeService;
import fan.summer.ui.setting.SwissKitJSettingUi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javafx.animation.Interpolator;
import javafx.animation.ScaleTransition;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Left navigation sidebar displaying all available tool categories and
 * quick-access items (AI Chat, Plugin Store, Settings). Each navigation
 * item shows an icon, label, and an optional badge for counts (e.g. plugin count).
 * <p>
 * Navigation selection is communicated via the {@link #setOnCategorySelect}
 * callback. The sidebar also supports a dedicated settings callback via
 * {@link #setOnSettingsSelect}.
 *
 * @see NavItem
 * @since 1.0
 */
public class Sidebar extends VBox {

    private static final Logger LOG = LoggerFactory.getLogger(Sidebar.class);

    public record Category(String id, String icon, String label, int count, boolean isNew) {}

    private final VBox content = new VBox();
    private final List<NavItem> navItems = new ArrayList<>();
    /** Every NavItem shown in the sidebar (categories + settings/about/theme), so collapse can reach them all. */
    private final List<NavItem> allItems = new ArrayList<>();
    /** Section heading labels, hidden (un-managed) when collapsed for a clean icon strip. */
    private final List<Label> sectionLabels = new ArrayList<>();
    private NavItem activeItem;
    private NavItem themeItem;
    private Consumer<String> onCategorySelect;
    private Runnable onSettingsSelect;
    private Runnable onAboutSelect;

    /** Whether the sidebar is currently in icon-only (collapsed) mode. Persisted via settings. */
    private boolean collapsed = "true".equalsIgnoreCase(readCollapsedPref());

    /** Collapse toggle button. Field so {@link #toggleCollapse()} can update its text. */
    private Label collapseBtn;

    public Sidebar() {
        LOG.info("Sidebar initializing");
        getStyleClass().add("sidebar");
        setSpacing(0);
        build();
        // Apply persisted collapse state so it renders correctly on first paint.
        // This toggles the CSS class AND drops labels/badges out of layout.
        applyCollapsed(collapsed);
        // Keep the theme toggle icon/label in sync regardless of where the change
        // originated (sidebar click OR settings-page combo).
        ThemeService.onChange(t -> javafx.application.Platform.runLater(() -> {
            if (themeItem != null) {
                boolean dark = t == ThemeService.Theme.DARK;
                themeItem.setIcon(dark ? "weather-night" : "weather-sunny");
                themeItem.setText(I18n.get(dark ? "sidebar.label.theme.dark" : "sidebar.label.theme.light"));
            }
        }));
        LOG.info("Sidebar initialized with {} nav items", navItems.size());
    }

    /**
     * Sets the consumer to receive category ID strings whenever the user clicks
     * a navigation item (excluding Settings).
     *
     * @param handler a consumer that receives the selected category ID; may be null
     */
    public void setOnCategorySelect(Consumer<String> handler) {
        LOG.debug("setOnCategorySelect callback set");
        this.onCategorySelect = handler;
    }

    /**
     * Sets the runnable to execute when the user clicks the Settings item.
     *
     * @param handler the runnable to execute; may be null
     */
    public void setOnSettingsSelect(Runnable handler) {
        LOG.debug("setOnSettingsSelect callback set");
        this.onSettingsSelect = handler;
    }

    /**
     * Sets the runnable to execute when the user clicks the About item.
     *
     * @param handler the runnable to execute; may be null
     */
    public void setOnAboutSelect(Runnable handler) {
        LOG.debug("setOnAboutSelect callback set");
        this.onAboutSelect = handler;
    }

    /** Dynamically update plugin category badge numbers */
    public void updateBadge(String categoryId, int count) {
        LOG.debug("Updating badge for category: id={}, count={}", categoryId, count);
        navItems.stream()
            .filter(item -> item.getCategoryId().equals(categoryId))
            .findFirst()
            .ifPresent(item -> item.setBadge(count));
    }

    // ── Build static navigation structure ──────────────────────────────────

    private void build() {
        content.setSpacing(0);

        // ── Collapse toggle (very top of sidebar) ─────────────────────
        collapseBtn = new Label(collapsed ? "»" : "«");
        collapseBtn.getStyleClass().add("sidebar-collapse-btn");
        collapseBtn.setMaxWidth(Double.MAX_VALUE);
        collapseBtn.setAlignment(Pos.CENTER_RIGHT);
        collapseBtn.setOnMouseClicked(e -> toggleCollapse());
        content.getChildren().add(collapseBtn);

        // ── AI section (first position) ────────────────────────────────
        content.getChildren().add(sectionLabel("sidebar.section.aiAssistant"));
        addNavItem("ai", "robot-outline", "sidebar.label.aiChat", 0, true);

        content.getChildren().add(divider());

        // ── Tools section ────────────────────────────────────
        content.getChildren().add(sectionLabel("sidebar.section.tools"));
        addNavItem("all",     "view-grid",             "sidebar.label.allTools",        0, false);
        addNavItem("text",    "form-textbox",          "sidebar.label.textProcessing",  0, false);
        addNavItem("image",   "image-outline",         "sidebar.label.imageProcessing", 0, false);
        addNavItem("dev",     "code-tags",             "sidebar.label.developerTools",  0, false);
        addNavItem("net",     "web",                   "sidebar.label.networkTools",    0, false);
        addNavItem("other",   "package-variant-closed","sidebar.label.otherTools",      0, false);

        content.getChildren().add(divider());

        // ── Plugins section ────────────────────────────────────
        content.getChildren().add(sectionLabel("sidebar.section.plugins"));
        addNavItem("plugins", "puzzle-outline", "sidebar.label.installedPlugins", 0, true);
        addNavItem("store",   "store-outline",  "sidebar.label.pluginStore",   0, false);

        content.getChildren().add(divider());

        // ── Favorites section ────────────────────────────────────
        content.getChildren().add(sectionLabel("sidebar.section.favorites"));
        addNavItem("fav", "star-outline", "sidebar.label.myFavorites", 0, false);

        content.getChildren().add(divider());

        // ── Settings (always at bottom) ──────────────────────────
        addSettingsItem("cog-outline", "sidebar.label.settings");
        addAboutItem("information-outline", "sidebar.label.about");
        addThemeToggleItem();

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

    private void addNavItem(String id, String mdiIcon, String i18nKey, int count, boolean isNew) {
        LOG.debug("Adding nav item: id={}, i18nKey={}", id, i18nKey);
        String label = I18n.get(i18nKey);
        NavItem item = new NavItem(id, mdiIcon, label, count, isNew);
        item.setOnMouseClicked(e -> activate(item, true));
        navItems.add(item);
        registerItem(item);
        I18n.bind(item.textLabelProperty(), i18nKey);
    }

    private void addSettingsItem(String mdiIcon, String i18nKey) {
        String label = I18n.get(i18nKey);
        NavItem item = new NavItem("settings", mdiIcon, label, 0, false);
        item.setOnMouseClicked(e -> {
            if (onSettingsSelect != null) onSettingsSelect.run();
        });
        registerItem(item);
        I18n.bind(item.textLabelProperty(), i18nKey);
    }

    private void addAboutItem(String mdiIcon, String i18nKey) {
        String label = I18n.get(i18nKey);
        NavItem item = new NavItem("about", mdiIcon, label, 0, false);
        item.setOnMouseClicked(e -> {
            if (onAboutSelect != null) onAboutSelect.run();
        });
        registerItem(item);
        I18n.bind(item.textLabelProperty(), i18nKey);
    }

    /**
     * Adds a NavItem to the sidebar content AND the {@link #allItems} tracking list
     * (so {@link #applyCollapsed(boolean)} can reach every item, including the
     * settings/about/theme footer items that are not in the {@link #navItems} category list).
     */
    private void registerItem(NavItem item) {
        content.getChildren().add(item);
        allItems.add(item);
    }

    /**
     * Builds the dark/light theme toggle item shown in the sidebar footer.
     * The item does NOT become the active category (mirrors settings/about
     * behavior). Its icon (weather-night/weather-sunny) and label are kept
     * in sync by the {@link ThemeService#onChange} listener registered in the
     * constructor, so changes from the settings page also update this item.
     */
    private void addThemeToggleItem() {
        boolean dark = ThemeService.current() == ThemeService.Theme.DARK;
        themeItem = new NavItem("theme",
                dark ? "weather-night" : "weather-sunny",
                I18n.get(dark ? "sidebar.label.theme.dark" : "sidebar.label.theme.light"),
                0, false);
        themeItem.setOnMouseClicked(e -> {
            ThemeService.Theme next = (ThemeService.current() == ThemeService.Theme.DARK)
                ? ThemeService.Theme.LIGHT : ThemeService.Theme.DARK;
            applyTheme(next);
        });
        registerItem(themeItem);
        // NOTE: i18n-binding the label is skipped intentionally — the label text
        // depends on the theme (dark vs. light wording), not just the locale.
        // The onChange listener updates it on both locale/theme switches.
    }

    /**
     * Applies the given theme: updates the global {@link ThemeService} (which
     * re-stamps the theme class on the scene root and fires listeners — the
     * listener in the constructor handles the icon/label refresh) and persists
     * the choice via {@link SwissKitJSettingUi#saveThemeSetting}.
     */
    private void applyTheme(ThemeService.Theme theme) {
        ThemeService.set(theme);
        SwissKitJSettingUi.saveThemeSetting(theme == ThemeService.Theme.DARK ? "dark" : "light");
    }

    private void activate(NavItem item, boolean fireEvent) {
        LOG.info("Activating nav item: id={}, fireEvent={}", item.getCategoryId(), fireEvent);
        if (activeItem != null) activeItem.setActive(false);
        activeItem = item;
        item.setActive(true);
        if (fireEvent && onCategorySelect != null) {
            onCategorySelect.accept(item.getCategoryId());
        }
    }

    // ── Helper node factory ──────────────────────────────────────

    private Label sectionLabel(String i18nKey) {
        Label l = new Label(I18n.get(i18nKey).toUpperCase());
        l.getStyleClass().add("sidebar-section-label");
        sectionLabels.add(l);
        I18n.bind(l.textProperty(), i18nKey);
        return l;
    }

    /**
     * Toggles the sidebar between the expanded (labeled list) and collapsed
     * (48px icon-only strip) states, persists the new state, and re-applies it.
     */
    private void toggleCollapse() {
        collapsed = !collapsed;
        applyCollapsed(collapsed);
        collapseBtn.setText(collapsed ? "»" : "«");
        SwissKitJSettingUi.saveSettingAsync("sidebar.collapsed", collapsed ? "true" : "false", null);
    }

    /**
     * Applies the collapsed/expanded state: toggles the {@code collapsed} CSS class
     * (which sets the 48px width and centers nav icons), drops the text label and
     * badge of every nav item out of layout (so the icon truly centers — CSS
     * {@code -fx-opacity:0} alone leaves them occupying space), and hides section
     * headings for a clean icon strip.
     */
    private void applyCollapsed(boolean c) {
        if (c) {
            if (!getStyleClass().contains("collapsed")) getStyleClass().add("collapsed");
        } else {
            getStyleClass().removeAll("collapsed");
        }
        for (NavItem item : allItems) {
            item.setCollapsed(c);
        }
        for (Label sl : sectionLabels) {
            sl.setManaged(!c);
            sl.setVisible(!c);
        }
    }

    /**
     * Reads the persisted collapse preference from the settings cache.
     * Returns {@code "false"} on any failure (degrades to expanded safely).
     */
    private static String readCollapsedPref() {
        try {
            String v = SwissKitJSettingUi.getSetting("sidebar.collapsed");
            return v == null ? "false" : v;
        } catch (Exception e) {
            return "false";
        }
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

    /**
     * A single navigation item rendered inside the Sidebar.
     * Displays an icon, a label (which may be i18n-bound), and an optional badge.
     * Supports visual activation state with a subtle scale animation.
     */
    public static class NavItem extends HBox {

        private final String categoryId;
        private final Label  textLabel;
        private final Label  badgeLabel;
        private Text   iconNode;
        private boolean active = false;

        /**
         * Constructs a NavItem with the given display properties.
         *
         * @param id        the category identifier this item represents
         * @param mdiIcon   the Material Design Icons name, e.g. {@code "robot-outline"}
         * @param label     the display label; may be i18n-bound for reactive updates
         * @param count     the initial badge count (0 means no badge shown)
         * @param isNew     whether to style this badge with a "new" indicator
         */
        public NavItem(String id, String mdiIcon, String label, int count, boolean isNew) {
            this.categoryId = id;

            getStyleClass().add("nav-item");
            setAlignment(Pos.CENTER_LEFT);
            setSpacing(10);
            setPrefHeight(34);

            // NOTE(theme): inline-styled icon fills can't reference -sk-* tokens, so these use the dark-palette
            // hexes. Icons won't follow the light theme in v3.2.0 (dark default). TODO(theme): move fill to CSS.
            iconNode = MdiIconUtil.createIcon(mdiIcon, 16, "-fx-fill: #9AA0A6;");
            iconNode.getStyleClass().add("nav-item-icon");

            textLabel = new Label(label);
            textLabel.getStyleClass().add("nav-item-text");
            HBox.setHgrow(textLabel, Priority.ALWAYS);

            badgeLabel = new Label(count > 0 ? String.valueOf(count) : "");
            badgeLabel.getStyleClass().add("nav-badge");
            if (isNew) badgeLabel.getStyleClass().add("nav-badge-new");
            badgeLabel.setVisible(count > 0);

            getChildren().addAll(iconNode, textLabel, badgeLabel);
            setCursor(javafx.scene.Cursor.HAND);

            // Hover: brighten icon (inline style overrides CSS, so we handle via Java)
            setOnMouseEntered(e -> {
                if (!active) iconNode.setStyle("-fx-fill: #D0D0D0;");
            });
            setOnMouseExited(e -> {
                if (!active) iconNode.setStyle("-fx-fill: #9AA0A6;");
            });
        }

        /**
         * Returns the category identifier for this nav item.
         *
         * @return the category ID passed at construction time
         */
        public String getCategoryId() { return categoryId; }

        /**
         * Returns the text label's text property so callers can bind it to i18n.
         *
         * @return the StringProperty of the text label
         */
        public StringProperty textLabelProperty() { return textLabel.textProperty(); }

        /**
         * Sets the visual active state of this item, adding or removing the
         * {@code active} CSS class and playing a brief scale animation.
         *
         * @param active true to mark this item as active; false to deactivate it
         */
        public void setActive(boolean active) {
            this.active = active;
            if (active) {
                getStyleClass().add("active");
                iconNode.setStyle("-fx-fill: #3574F0;");
                ScaleTransition st = new ScaleTransition(Duration.millis(160), this);
                st.setFromX(0.97); st.setFromY(0.97);
                st.setToX(1.0); st.setToY(1.0);
                st.setInterpolator(Interpolator.SPLINE(0.34, 0.9, 0.64, 1.0));
                st.play();
            } else {
                getStyleClass().remove("active");
                iconNode.setStyle("-fx-fill: #9AA0A6;");
            }
        }

        /**
         * Updates the badge display with the given count.
         * If count is zero or negative, the badge is hidden entirely.
         *
         * @param count the count to display in the badge
         */
        public void setBadge(int count) {
            badgeLabel.setText(count > 0 ? String.valueOf(count) : "");
            badgeLabel.setVisible(count > 0);
        }

        public boolean isActive() { return active; }

        /**
         * Switches this item into/out of icon-only mode. When collapsed, the text
         * label and badge are removed from layout ({@code managed=false}) so the
         * icon can center inside the 48px strip — {@code -fx-opacity:0} via CSS
         * is not enough, because an unmanaged-but-opaque label still consumes
         * horizontal space (it has {@code Hgrow.ALWAYS}) and pins the icon left.
         *
         * @param collapsed {@code true} to show only the icon, {@code false} to restore the full row
         */
        public void setCollapsed(boolean collapsed) {
            textLabel.setManaged(!collapsed);
            textLabel.setVisible(!collapsed);
            badgeLabel.setManaged(!collapsed);
            // Badge is only relevant when expanded AND it has a count.
            badgeLabel.setVisible(!collapsed && !badgeLabel.getText().isEmpty());
        }

        /**
         * Swaps the icon glyph, preserving the active/inactive fill color.
         * Used by the theme toggle to flip between weather-night/weather-sunny.
         *
         * @param mdiIcon the Material Design Icons name, e.g. {@code "weather-sunny"}
         */
        public void setIcon(String mdiIcon) {
            Text t = MdiIconUtil.createIcon(mdiIcon, 16,
                active ? "-fx-fill: #3574F0;" : "-fx-fill: #9AA0A6;");
            t.getStyleClass().add("nav-item-icon");
            getChildren().set(getChildren().indexOf(iconNode), t);
            iconNode = t;
        }

        /**
         * Updates the label text. Used by the theme toggle to reflect the new
         * theme's wording (Dark Theme / Light Theme).
         *
         * @param text the new label text
         */
        public void setText(String text) {
            textLabel.setText(text);
        }
    }
}
