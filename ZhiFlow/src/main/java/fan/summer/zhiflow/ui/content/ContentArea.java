package fan.summer.zhiflow.ui.content;

import fan.summer.zhiflow.api.i18n.I18n;
import fan.summer.zhiflow.api.ZhiFlowPlugin;
import fan.summer.zhiflow.plugin.FavoriteService;
import fan.summer.zhiflow.plugin.PluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.util.List;
import java.util.function.Consumer;

/**
 * Main content area of the application, displayed between the Sidebar and StatusBar.
 * Contains a search bar, a scrollable tool grid, a detail panel that slides in from
 * the right, and a switchable page stack for custom views (e.g. Settings, Plugin Store).
 * <p>
 * Plugin data is bound via {@link #setPlugins(ObservableList)} and the grid
 * automatically refreshes when plugins are added or removed. Category filtering
 * and search queries are applied client-side against the bound list.
 *
 * @see ToolCard
 * @see DetailPanel
 * @since 1.0
 */
public class ContentArea extends BorderPane {

    private static final Logger LOG = LoggerFactory.getLogger(ContentArea.class);

    // ── Sub-components ────────────────────────────────────────────
    private final TextField   searchField  = new TextField();
    private final FlowPane    toolGrid     = new FlowPane();
    private final DetailPanel detailPanel  = new DetailPanel();
    private final StackPane   pageStack    = new StackPane();
    private final ScrollPane  scrollPane;
    private final ScrollPane  pageScrollPane;
    private final HBox        backBar      = buildBackBar();

    // ── State ──────────────────────────────────────────────
    private ObservableList<ZhiFlowPlugin> plugins;
    private String   currentCategory = "all";
    private String   currentQuery    = "";
    private Consumer<ZhiFlowPlugin> onLaunch;
    private Consumer<ZhiFlowPlugin> onUninstall;
    private Runnable onBack;
    private PluginRegistry registry;
    private FavoriteService favoriteService;

    public ContentArea() {
        LOG.info("ContentArea initializing");
        scrollPane     = buildScrollPane();
        pageScrollPane = buildPageScrollPane();
        buildLayout();
        detailPanel.setOnLaunch(p -> { if (onLaunch != null) onLaunch.accept(p); });
        detailPanel.setOnUninstall(p -> { if (onUninstall != null) onUninstall.accept(p); });
        detailPanel.setOnFavoriteToggle(p -> refresh());
        I18n.addListener(() -> javafx.application.Platform.runLater(this::refresh));
        LOG.info("ContentArea initialized");
    }

    // ── Public API ──────────────────────────────────────────

    /**
     * Sets the callback invoked when the user clicks the Launch button in the detail panel.
     *
     * @param handler the consumer that receives the selected plugin; must not be null
     */
    public void setOnLaunch(Consumer<ZhiFlowPlugin> handler) {
        LOG.debug("setOnLaunch callback set");
        this.onLaunch = handler;
    }

    /**
     * Sets the callback invoked when the user confirms uninstalling a plugin from the detail panel.
     *
     * @param handler the consumer that receives the plugin to uninstall; must not be null
     */
    public void setOnUninstall(Consumer<ZhiFlowPlugin> handler) {
        this.onUninstall = handler;
    }

    /**
     * Sets the callback invoked when the user navigates back from an active tool view.
     *
     * @param handler the runnable to execute on back navigation; may be null
     */
    public void setOnBack(Runnable handler) {
        LOG.debug("setOnBack callback set");
        this.onBack = handler;
    }

    /**
     * Sets the plugin registry for querying background state (used by ToolCard indicators).
     *
     * @param registry the PluginRegistry; must not be null
     */
    public void setRegistry(PluginRegistry registry) {
        this.registry = registry;
    }

    /**
     * Sets the favorite service for querying favorite state (used by ToolCard star icon).
     *
     * @param favoriteService the FavoriteService; must not be null
     */
    public void setFavoriteService(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    /**
     * Binds the plugin list to this content area, automatically refreshing the tool grid
     * whenever plugins are added or removed.
     *
     * @param list the observable list of plugins to display; must not be null
     */
    public void setPlugins(ObservableList<ZhiFlowPlugin> list) {
        LOG.info("Binding plugin list with {} plugins", list.size());
        this.plugins = list;
        list.addListener((ListChangeListener<ZhiFlowPlugin>) c -> refresh());
        refresh();
    }

    /**
     * Filters the tool grid to show only plugins in the specified category
     * and clears any active search query.
     *
     * @param categoryId the category identifier ({@code "all"}, {@code "text"}, {@code "image"},
     *                   {@code "dev"}, {@code "net"}, {@code "other"}, {@code "plugins"}, etc.)
     */
    public void showCategory(String categoryId) {
        LOG.info("Showing category: id={}", categoryId);
        currentCategory = categoryId;
        searchField.clear();
        currentQuery = "";
        setTopMode(false, null);
        crossFadeTo(scrollPane);
        refresh();
        animateGridIn();
    }

    /**
     * Switches the center content to a custom page (such as Settings or Plugin Store),
     * hiding the tool grid and the detail panel.
     *
     * @param page the JavaFX Node to display as the center content; must not be null
     * @param title the title to display in the back bar; may be null
     */
    public void showPage(Node page, String title) {
        LOG.info("Showing page: title={}", title);
        pageScrollPane.setContent(page);
        setTopMode(true, title);
        crossFadeTo(pageScrollPane);
        detailPanel.hide();
    }

    /** Back to tool grid home */
    public void showToolGrid() {
        LOG.info("Returning to tool grid");
        setTopMode(false, null);
        refresh();
        crossFadeTo(scrollPane);
    }

    /**
     * Focuses the search field if it is currently visible (tool-grid mode).
     * Invoked by the global Cmd/Ctrl+K accelerator.
     */
    public void focusSearch() {
        if (searchField.getParent() != null && searchField.getParent().isVisible()) {
            searchField.requestFocus();
            searchField.selectAll();
        }
    }

    /**
     * Handles a global Escape press: closes the detail panel first,
     * then clears an active search query.
     *
     * @return {@code true} if the event was consumed
     */
    public boolean handleEscape() {
        if (detailPanel.isPanelOpen()) {
            detailPanel.hide();
            return true;
        }
        if (!currentQuery.isEmpty()) {
            searchField.clear();
            return true;
        }
        return false;
    }

    private void setTopMode(boolean pageMode, String title) {
        if (pageMode) {
            Label titleLabel = (Label) backBar.lookup(".back-title");
            if (titleLabel != null) titleLabel.setText(title != null ? title : "");
            backBar.setVisible(true);
            backBar.setManaged(true);
            searchField.getParent().setVisible(false);
            searchField.getParent().setManaged(false);
        } else {
            backBar.setVisible(false);
            backBar.setManaged(false);
            searchField.getParent().setVisible(true);
            searchField.getParent().setManaged(true);
        }
    }

    // ── Layout build ──────────────────────────────────────────

    private void buildLayout() {
        // Search bar
        HBox searchBar = buildSearchBar();

        // Top area: back bar (hidden by default) + search bar
        backBar.setVisible(false);
        backBar.setManaged(false);
        VBox top = new VBox(backBar, searchBar);
        top.setPadding(new Insets(12, 16, 0, 16));
        setTop(top);

        // Center: pageStack (switchable content) with DetailPanel overlaid on the right.
        // Stacking instead of side-by-side keeps the tool grid width constant while the
        // panel slides in, so FlowPane doesn't reflow mid-animation.
        pageStack.getChildren().add(scrollPane);

        StackPane center = new StackPane(pageStack, detailPanel);
        center.setMaxWidth(Double.MAX_VALUE);
        center.setMaxHeight(Double.MAX_VALUE);
        StackPane.setAlignment(detailPanel, Pos.TOP_RIGHT);
        detailPanel.setPickOnBounds(false);
        setCenter(center);
    }

    private HBox buildBackBar() {
        Label backBtn = new Label("← " + I18n.get("content.back"));
        backBtn.getStyleClass().add("back-btn");
        // Hover highlight is handled in CSS (.back-btn:hover) so it follows the theme.
        backBtn.setOnMouseClicked(e -> {
            if (onBack != null) onBack.run();
            showToolGrid();
        });

        Label sep = new Label("/");
        sep.getStyleClass().add("back-sep");

        Label titleLabel = new Label();
        titleLabel.getStyleClass().add("back-title");

        HBox bar = new HBox(6, backBtn, sep, titleLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPrefHeight(38);
        return bar;
    }

    private HBox buildSearchBar() {
        Label searchIcon = new Label("🔍");
        searchIcon.getStyleClass().add("search-icon");

        searchField.getStyleClass().add("search-field");
        searchField.setPromptText(I18n.get("content.search.prompt"));
        searchField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            currentQuery = newVal.trim().toLowerCase();
            isSearchRefresh = true;
            refresh();
            isSearchRefresh = false;
        });

        boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        Label kbdHint = new Label(mac ? "⌘K" : "Ctrl+K");
        kbdHint.getStyleClass().add("search-kbd");

        HBox bar = new HBox(10, searchIcon, searchField, kbdHint);
        bar.getStyleClass().add("search-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPrefHeight(38);
        return bar;
    }

    private ScrollPane buildPageScrollPane() {
        ScrollPane sp = new ScrollPane();
        sp.getStyleClass().add("content-scroll");
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sp.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        // Control's default maxWidth is USE_COMPUTED_SIZE, which equals prefWidth.
        // Force MAX_VALUE so StackPane can stretch this to fill the page area.
        sp.setMaxWidth(Double.MAX_VALUE);
        sp.setMaxHeight(Double.MAX_VALUE);
        return sp;
    }

    private ScrollPane buildScrollPane() {
        toolGrid.setHgap(10);
        toolGrid.setVgap(10);
        toolGrid.setPadding(new Insets(16));

        VBox wrapper = new VBox(
            sectionHeader("content.section.frequent", ""),
            toolGrid
        );
        wrapper.setPadding(new Insets(8, 16, 16, 16));
        wrapper.setSpacing(0);

        ScrollPane sp = new ScrollPane(wrapper);
        sp.getStyleClass().add("content-scroll");
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sp.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        sp.setMaxWidth(Double.MAX_VALUE);
        sp.setMaxHeight(Double.MAX_VALUE);

        // Dynamic wrap length: viewport width minus 32px wrapper padding (16 left + 16 right)
        toolGrid.prefWrapLengthProperty().bind(
            sp.viewportBoundsProperty().map(b -> Math.max(b.getWidth() - 32, 0))
        );

        return sp;
    }

    // ── Grid refresh ──────────────────────────────────────────

    /**
     * Whether the current refresh is triggered by a search query change.
     * When true, entry animations are suppressed to avoid flicker during typing.
     */
    private boolean isSearchRefresh = false;

    private void refresh() {
        if (plugins == null) return;

        List<ZhiFlowPlugin> filtered = plugins.stream()
            .filter(this::matchesCategory)
            .filter(this::matchesQuery)
            .toList();

        toolGrid.getChildren().clear();

        // Skip staggered animations when refreshing due to search typing —
        // only animate on category switch (showCategory) which calls animateGridIn().
        boolean animate = !isSearchRefresh;
        // Limit staggered animations to the first batch; cards beyond this
        // are added immediately to avoid creating hundreds of PauseTransitions.
        int staggerLimit = animate ? Math.min(filtered.size(), 30) : 0;

        for (int i = 0; i < filtered.size(); i++) {
            ZhiFlowPlugin p = filtered.get(i);
            ToolCard card = new ToolCard(p, this::onCardSelect, registry, favoriteService);
            card.setPrefWidth(152);
            card.setPrefHeight(130);

            if (i < staggerLimit) {
                // Staggered entry animation for visible cards
                int delay = i * 35;
                card.setOpacity(0);
                PauseTransition pause = new PauseTransition(Duration.millis(delay));
                pause.setOnFinished(e -> {
                    FadeTransition ft = new FadeTransition(Duration.millis(240), card);
                    ft.setFromValue(0); ft.setToValue(1);
                    TranslateTransition tt = new TranslateTransition(Duration.millis(240), card);
                    tt.setFromY(10); tt.setToY(0);
                    new ParallelTransition(ft, tt).play();
                });
                pause.play();
            } else {
                // Cards beyond the stagger limit (or during search) appear immediately
                card.setOpacity(1);
            }

            toolGrid.getChildren().add(card);
        }

        // Empty state message — differentiated: search miss vs. empty category
        if (filtered.isEmpty()) {
            String msg = currentQuery.isEmpty()
                ? I18n.get("content.emptyState.category")
                : I18n.get("content.emptyState.search", currentQuery);
            Label empty = new Label(msg);
            empty.getStyleClass().add("sk-t3");
            empty.setStyle("-fx-font-size: 13px;");
            empty.setPadding(new Insets(40, 0, 0, 0));
            toolGrid.getChildren().add(empty);
        }
    }

    // ── Filter logic ──────────────────────────────────────────

    private boolean matchesCategory(ZhiFlowPlugin p) {
        return switch (currentCategory) {
            case "all"     -> true;
            case "plugins" -> p.getType().isPlugin();
            case "fav"     -> favoriteService != null && favoriteService.isFavorite(p.getId());
            case "ai"      -> false; // AI chat is launched directly from sidebar, not shown as card
            default        -> p.getCategory().getId().equalsIgnoreCase(currentCategory);
        };
    }

    private boolean matchesQuery(ZhiFlowPlugin p) {
        if (currentQuery.isEmpty()) return true;
        return p.getName().toLowerCase().contains(currentQuery)
            || p.getDescription().toLowerCase().contains(currentQuery);
    }

    // ── Card selection ──────────────────────────────────────────

    private void onCardSelect(ZhiFlowPlugin plugin) {
        LOG.info("Card selected: plugin={}", plugin.getName());
        detailPanel.show(plugin);
    }

    // ── Page transition animation (cross-fade) ──────────────────────

    private void crossFadeTo(Node next) {
        Node current = pageStack.getChildren().isEmpty()
            ? null : pageStack.getChildren().get(0);

        next.setOpacity(0);
        if (!pageStack.getChildren().contains(next))
            pageStack.getChildren().add(next);
        next.toFront();

        FadeTransition fadeIn = new FadeTransition(Duration.millis(220), next);
        fadeIn.setToValue(1);

        if (current != null && current != next) {
            FadeTransition fadeOut = new FadeTransition(Duration.millis(180), current);
            fadeOut.setToValue(0);
            Node finalCurrent = current;
            fadeOut.setOnFinished(e -> pageStack.getChildren().remove(finalCurrent));
            new ParallelTransition(fadeOut, fadeIn).play();
        } else {
            fadeIn.play();
        }
    }

    private void animateGridIn() {
        // Grid slides in from slightly below
        TranslateTransition tt = new TranslateTransition(Duration.millis(280), toolGrid);
        tt.setFromY(12); tt.setToY(0);
        FadeTransition ft = new FadeTransition(Duration.millis(280), toolGrid);
        ft.setFromValue(0.4); ft.setToValue(1);
        new ParallelTransition(tt, ft).play();
    }

    // ── Helper node factory ──────────────────────────────────────

    private HBox sectionHeader(String titleKey, String action) {
        Label titleLabel = new Label(I18n.get(titleKey).toUpperCase());
        titleLabel.getStyleClass().add("section-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(titleLabel, spacer);
        if (!action.isEmpty()) {
            Label actionLabel = new Label(action);
            actionLabel.setStyle("-fx-text-fill: #3574F0; -fx-font-size: 12px; -fx-cursor: hand;");
            row.getChildren().add(actionLabel);
        }
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 0, 10, 0));
        return row;
    }
}
