package fan.summer.api.preview;

import fan.summer.api.SwissKitJPlugin;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the full shell layout: title bar, search bar, sidebar, content area,
 * detail panel, status bar. The public PluginPreviewWindow delegates to this.
 */
class PreviewShell extends BorderPane {

    private final List<SwissKitJPlugin> plugins = new ArrayList<>();
    private SwissKitJPlugin activePlugin;

    private final PreviewDetailPanel detailPanel = new PreviewDetailPanel();
    private final FlowPane            toolGrid    = new FlowPane();
    private final StackPane           pageStack   = new StackPane();
    private final HBox                backBar;
    private final TextField           searchField = new TextField();
    private final Label               statusLabel = new Label();
    private final PreviewTitleBar     titleBar;
    private final Node                sidebarNode;
    private final Node                searchBarNode;
    private final Node                statusBarNode;

    private final boolean showSidebar;
    private final boolean showSearchBar;
    private final boolean showStatusBar;
    private final boolean showDetailPanel;
    private final Runnable onClose;

    PreviewShell(List<SwissKitJPlugin> plugins, String title,
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
        setStyle(
            "-fx-background-color: rgba(13,14,17,0.72);" +
            "-fx-background-radius: 20;" +
            "-fx-border-radius: 20;" +
            "-fx-border-color: rgba(255,255,255,0.10);" +
            "-fx-border-width: 1;"
        );

        titleBar = new PreviewTitleBar(title);
        backBar = buildBackBar();
        backBar.setVisible(false);
        backBar.setManaged(false);

        sidebarNode = new PreviewSidebar();
        searchBarNode = buildSearchBar();
        statusBarNode = buildStatusBar();

        buildLayout();
        wireEvents();
    }

    void close() {
        for (SwissKitJPlugin p : plugins) {
            try {
                if (p == activePlugin) p.onDeactivate();
                p.onUnload();
            } catch (Exception ignored) {}
        }
        if (onClose != null) onClose.run();
    }

    // ── Layout ─────────────────────────────────────────────────

    private void buildLayout() {
        // Title bar (always visible)
        setTop(titleBar);

        // Body
        HBox body = new HBox();
        if (showSidebar) {
            body.getChildren().add(sidebarNode);
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
        searchIcon.setStyle("-fx-font-size: 13px; -fx-text-fill: rgba(255,255,255,0.28);");

        searchField.getStyleClass().add("preview-search-field");
        searchField.setPromptText("Search tools...");
        searchField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> refreshToolGrid());

        HBox bar = new HBox(10, searchIcon, searchField);
        bar.getStyleClass().add("preview-search-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPrefHeight(38);
        return bar;
    }

    // ── Back bar ────────────────────────────────────────────────

    private HBox buildBackBar() {
        Label backBtn = new Label("← Back");
        backBtn.getStyleClass().add("preview-back-btn");
        backBtn.setOnMouseClicked(e -> showToolGrid());

        Label sep = new Label("/");
        sep.setStyle("-fx-text-fill: rgba(255,255,255,0.25); -fx-font-size: 13px; -fx-padding: 4 6 4 0;");

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

        statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.28); -fx-font-size: 12px;");
        bar.getChildren().add(statusLabel);
        updateStatus();
        return bar;
    }

    private void updateStatus() {
        statusLabel.setText(plugins.size() + " plugin(s) loaded");
    }

    // ── Event wiring ────────────────────────────────────────────

    private void wireEvents() {
        detailPanel.setOnLaunch(plugin -> {
            activePlugin = plugin;
            try { plugin.onActivate(); } catch (Exception ignored) {}
            showPluginView(plugin);
        });
    }

    // ── Tool grid ───────────────────────────────────────────────

    private void refreshToolGrid() {
        toolGrid.getChildren().clear();
        String query = searchField.getText().trim().toLowerCase();

        List<SwissKitJPlugin> filtered = plugins.stream()
            .filter(p -> query.isEmpty()
                || p.getName().toLowerCase().contains(query)
                || p.getDescription().toLowerCase().contains(query))
            .toList();

        for (SwissKitJPlugin p : filtered) {
            PreviewToolCard card = new PreviewToolCard(p, this::onCardSelect);
            card.setPrefWidth(152);
            card.setPrefHeight(130);
            toolGrid.getChildren().add(card);
        }

        if (filtered.isEmpty()) {
            Label empty = new Label("No matching plugins found");
            empty.getStyleClass().add("preview-empty-text");
            empty.setPadding(new Insets(40, 0, 0, 0));
            toolGrid.getChildren().add(empty);
        }
    }

    private void onCardSelect(SwissKitJPlugin plugin) {
        if (showDetailPanel) {
            detailPanel.show(plugin);
        } else {
            // No detail panel — launch directly
            activePlugin = plugin;
            try { plugin.onActivate(); } catch (Exception ignored) {}
            showPluginView(plugin);
        }
    }

    // ── Page transitions ────────────────────────────────────────

    private void showPluginView(SwissKitJPlugin plugin) {
        detailPanel.hide();

        Label titleLabel = (Label) backBar.lookup(".preview-back-title");
        if (titleLabel != null) titleLabel.setText(plugin.getName());
        backBar.setVisible(true);
        backBar.setManaged(true);

        Node view;
        try {
            view = plugin.createView();
        } catch (Exception e) {
            Label errorLabel = new Label("Error creating view:\n" + e.getMessage());
            errorLabel.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 13px; -fx-padding: 20;");
            errorLabel.setWrapText(true);
            view = errorLabel;
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
            try { activePlugin.onDeactivate(); } catch (Exception ignored) {}
            activePlugin = null;
        }
        backBar.setVisible(false);
        backBar.setManaged(false);

        // Rebuild grid scroll
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
