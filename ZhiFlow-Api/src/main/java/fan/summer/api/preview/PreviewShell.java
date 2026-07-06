package fan.summer.api.preview;

import fan.summer.api.PluginContext;
import fan.summer.api.ZhiFlowPlugin;
import fan.summer.api.i18n.I18n;
import javafx.application.Platform;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assembles the full shell layout: title bar, search bar, sidebar, content area,
 * detail panel, status bar. The public PluginPreviewWindow delegates to this.
 */
class PreviewShell extends BorderPane {

    private final List<ZhiFlowPlugin> plugins = new ArrayList<>();
    private ZhiFlowPlugin activePlugin;
    private final Set<ZhiFlowPlugin> backgroundPlugins = new LinkedHashSet<>();
    private final Map<ZhiFlowPlugin, Node> cachedViews = new LinkedHashMap<>();

    private final PreviewDetailPanel detailPanel = new PreviewDetailPanel();
    private final FlowPane            toolGrid    = new FlowPane();
    private final StackPane           pageStack   = new StackPane();
    private final HBox                backBar;
    private final TextField           searchField = new TextField();
    private final Label               statusLabel = new Label();
    private final PreviewTitleBar     titleBar;
    private final PreviewSidebar      sidebar;
    private final Node                searchBarNode;
    private final Node                statusBarNode;

    private final boolean showSidebar;
    private final boolean showSearchBar;
    private final boolean showStatusBar;
    private final boolean showDetailPanel;
    private final Runnable onClose;

    PreviewShell(List<ZhiFlowPlugin> plugins, String title,
                 boolean showSidebar, boolean showSearchBar,
                 boolean showStatusBar, boolean showDetailPanel,
                 Runnable onClose) {
        this.plugins.addAll(plugins);
        this.showSidebar = showSidebar;
        this.showSearchBar = showSearchBar;
        this.showStatusBar = showStatusBar;
        this.showDetailPanel = showDetailPanel;
        this.onClose = onClose;

        getStyleClass().add("preview-root");
        // Surface colors/border/radius are defined entirely in .preview-root
        // (zhiflow-preview.css) via -sk-* tokens so the window re-resolves
        // correctly on theme switch. No inline color overrides here.

        titleBar = new PreviewTitleBar(title);
        backBar = buildBackBar();
        backBar.setVisible(false);
        backBar.setManaged(false);

        sidebar = new PreviewSidebar();
        searchBarNode = buildSearchBar();
        statusBarNode = buildStatusBar();

        buildLayout();
        wireEvents();
        applyI18n();
        // Re-apply all locale-dependent text whenever the language changes
        // (the title bar's language toggle fires I18n.setLocale → this listener).
        I18n.addListener(this::onLocaleChanged);
    }

    private void onLocaleChanged() {
        if (Platform.isFxApplicationThread()) {
            applyI18n();
        } else {
            Platform.runLater(this::applyI18n);
        }
    }

    /** Re-apply every locale-dependent string in the shell. */
    private void applyI18n() {
        searchField.setPromptText(I18n.get("content.search.prompt"));
        // Back button is the first child of backBar.
        if (!backBar.getChildren().isEmpty() && backBar.getChildren().get(0) instanceof Label backBtn) {
            backBtn.setText("← " + I18n.get("content.back"));
        }
        updateStatus();
        sidebar.refresh();
        titleBar.applyLocale();
        detailPanel.refreshLocale();
        // Cards carry the i18n "Plugin/Built-in" tag — rebuild so they update.
        refreshToolGrid();
    }

    boolean isBackground(ZhiFlowPlugin plugin) {
        return backgroundPlugins.contains(plugin);
    }

    void close() {
        for (ZhiFlowPlugin p : plugins) {
            try {
                if (p == activePlugin) PluginContext.runWith(p, p::onDeactivate);
                PluginContext.runWith(p, p::onUnload);
            } catch (Exception ignored) {}
        }
        backgroundPlugins.clear();
        cachedViews.clear();
        if (onClose != null) onClose.run();
    }

    // ── Layout ─────────────────────────────────────────────────

    private void buildLayout() {
        // Title bar (always visible)
        setTop(titleBar);

        // Body
        HBox body = new HBox();
        if (showSidebar) {
            body.getChildren().add(sidebar);
        }

        // Center: back bar + search bar + content
        VBox center = new VBox();
        center.getChildren().add(backBar);
        if (showSearchBar) {
            center.getChildren().add(searchBarNode);
            VBox.setMargin(searchBarNode, new Insets(12, 16, 0, 16));
        }

        // Content stack
        toolGrid.setHgap(10);
        toolGrid.setVgap(10);
        toolGrid.setPadding(new Insets(16));
        toolGrid.setPrefWrapLength(600);

        ScrollPane gridScroll = new ScrollPane(toolGrid);
        gridScroll.setFitToWidth(true);
        gridScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        gridScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        gridScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        pageStack.getChildren().add(gridScroll);
        pageStack.setAlignment(Pos.TOP_LEFT);

        StackPane centerPane = new StackPane(pageStack);
        if (showDetailPanel) {
            centerPane.getChildren().add(detailPanel);
            StackPane.setAlignment(detailPanel, Pos.TOP_RIGHT);
            detailPanel.setPickOnBounds(false);
        }

        VBox.setVgrow(centerPane, Priority.ALWAYS);
        center.getChildren().add(centerPane);
        HBox.setHgrow(center, Priority.ALWAYS);
        body.getChildren().add(center);

        // Status bar
        VBox mainArea = new VBox(body);
        if (showStatusBar) {
            mainArea.getChildren().add(statusBarNode);
        }
        VBox.setVgrow(body, Priority.ALWAYS);

        setCenter(mainArea);

        // Show initial tool cards
        refreshToolGrid();
    }

    void bindStage(Scene scene) {
        javafx.stage.Stage stage = (javafx.stage.Stage) scene.getWindow();
        titleBar.bindStage(stage, onClose);
    }

    // ── Search bar ──────────────────────────────────────────────

    private Node buildSearchBar() {
        Label searchIcon = new Label("🔍");
        searchIcon.getStyleClass().add("preview-search-icon");

        searchField.getStyleClass().add("preview-search-field");
        searchField.setPromptText(I18n.get("content.search.prompt"));
        searchField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> refreshToolGrid());

        Label kbdHint = new Label("⌘K");
        kbdHint.getStyleClass().add("preview-search-kbd");

        HBox bar = new HBox(10, searchIcon, searchField, kbdHint);
        bar.getStyleClass().add("preview-search-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPrefHeight(34);
        return bar;
    }

    // ── Back bar ────────────────────────────────────────────────

    private HBox buildBackBar() {
        Label backBtn = new Label("← " + I18n.get("content.back"));
        backBtn.getStyleClass().add("preview-back-btn");
        backBtn.setOnMouseClicked(e -> showToolGrid());

        Label sep = new Label("/");
        sep.getStyleClass().add("preview-back-sep");

        Label titleLabel = new Label();
        titleLabel.getStyleClass().add("preview-back-title");

        HBox bar = new HBox(6, backBtn, sep, titleLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPrefHeight(38);
        return bar;
    }

    // ── Status bar ──────────────────────────────────────────────

    private Node buildStatusBar() {
        HBox bar = new HBox();
        bar.getStyleClass().add("preview-statusbar");
        bar.setAlignment(Pos.CENTER_LEFT);

        statusLabel.getStyleClass().add("preview-status-text");
        bar.getChildren().add(statusLabel);
        updateStatus();
        return bar;
    }

    private void updateStatus() {
        statusLabel.setText(MessageFormat.format(I18n.get("status.tools"), plugins.size()));
    }

    // ── Event wiring ────────────────────────────────────────────

    private void wireEvents() {
        detailPanel.setOnLaunch(plugin -> {
            activePlugin = plugin;
            try { PluginContext.runWith(plugin, plugin::onActivate); } catch (Exception ignored) {}
            showPluginView(plugin);
        });
    }

    // ── Tool grid ───────────────────────────────────────────────

    private void refreshToolGrid() {
        toolGrid.getChildren().clear();
        String query = searchField.getText().trim().toLowerCase();

        List<ZhiFlowPlugin> filtered = plugins.stream()
            .filter(p -> query.isEmpty()
                || p.getName().toLowerCase().contains(query)
                || p.getDescription().toLowerCase().contains(query))
            .toList();

        for (ZhiFlowPlugin p : filtered) {
            PreviewToolCard card = new PreviewToolCard(p, this::onCardSelect, backgroundPlugins.contains(p));
            card.setPrefWidth(152);
            card.setPrefHeight(130);
            toolGrid.getChildren().add(card);
        }

        if (filtered.isEmpty()) {
            Label empty = new Label(I18n.get("content.emptyState"));
            empty.getStyleClass().add("preview-empty-text");
            empty.setPadding(new Insets(40, 0, 0, 0));
            toolGrid.getChildren().add(empty);
        }
    }

    private void onCardSelect(ZhiFlowPlugin plugin) {
        if (showDetailPanel) {
            detailPanel.show(plugin);
        } else {
            // No detail panel — launch directly
            activePlugin = plugin;
            try { PluginContext.runWith(plugin, plugin::onActivate); } catch (Exception ignored) {}
            showPluginView(plugin);
        }
    }

    // ── Page transitions ────────────────────────────────────────

    private void showPluginView(ZhiFlowPlugin plugin) {
        detailPanel.hide();

        if (showSearchBar) {
            searchBarNode.setVisible(false);
            searchBarNode.setManaged(false);
        }

        Label titleLabel = (Label) backBar.lookup(".preview-back-title");
        if (titleLabel != null) titleLabel.setText(plugin.getName());
        backBar.setVisible(true);
        backBar.setManaged(true);

        boolean fromBackground = backgroundPlugins.remove(plugin);

        Node view = cachedViews.get(plugin);
        if (view == null) {
            try {
                view = PluginContext.callWith(plugin, plugin::createView);
                if (view != null) PluginContext.wrapEvents(plugin, view);
                cachedViews.put(plugin, view);
            } catch (Exception e) {
                Label errorLabel = new Label("Error creating view:\n" + e.getMessage());
                errorLabel.getStyleClass().add("sk-danger-text");
                errorLabel.setStyle("-fx-font-size: 13px; -fx-padding: 20;");
                errorLabel.setWrapText(true);
                view = errorLabel;
            }
        }

        activePlugin = plugin;
        try { PluginContext.runWith(plugin, plugin::onActivate); } catch (Exception ignored) {}
        if (fromBackground) {
            try { PluginContext.runWith(plugin, plugin::onForeground); } catch (Exception ignored) {}
        }

        ScrollPane pageScroll = new ScrollPane(view);
        pageScroll.setFitToWidth(true);
        pageScroll.setFitToHeight(true);
        pageScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        pageScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        pageScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        crossFadeTo(pageScroll);
    }

    private void showToolGrid() {
        if (activePlugin != null) {
            if (activePlugin.hasRunningTasks()) {
                backgroundPlugins.add(activePlugin);
                try { PluginContext.runWith(activePlugin, activePlugin::onBackground); } catch (Exception ignored) {}
            } else {
                cachedViews.remove(activePlugin);
                try { PluginContext.runWith(activePlugin, activePlugin::onDeactivate); } catch (Exception ignored) {}
            }
            activePlugin = null;
        }

        if (showSearchBar) {
            searchBarNode.setVisible(true);
            searchBarNode.setManaged(true);
        }

        backBar.setVisible(false);
        backBar.setManaged(false);

        refreshToolGrid();

        ScrollPane gridScroll = new ScrollPane(toolGrid);
        gridScroll.setFitToWidth(true);
        gridScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        gridScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        gridScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        crossFadeTo(gridScroll);
    }

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
}
