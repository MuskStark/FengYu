package fan.summer.ui.content;

import fan.summer.api.SwissKitJPlugin;
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
 * Main content area.
 * Contains: Search bar / Tool grid / Detail panel / Page transition animations.
 * Bind plugin data via setPlugins(ObservableList), auto-respond to add/remove.
 */
public class ContentArea extends BorderPane {

    // ── Sub-components ────────────────────────────────────────────
    private final TextField   searchField  = new TextField();
    private final FlowPane    toolGrid     = new FlowPane();
    private final DetailPanel detailPanel  = new DetailPanel();
    private final StackPane   pageStack    = new StackPane();
    private final ScrollPane  scrollPane;
    private final ScrollPane  pageScrollPane;
    private final HBox        backBar      = buildBackBar();

    // ── State ──────────────────────────────────────────────
    private ObservableList<SwissKitJPlugin> plugins;
    private String   currentCategory = "all";
    private String   currentQuery    = "";
    private Consumer<SwissKitJPlugin> onLaunch;
    private Runnable onBack;

    public ContentArea() {
        scrollPane     = buildScrollPane();
        pageScrollPane = buildPageScrollPane();
        buildLayout();
        detailPanel.setOnLaunch(p -> { if (onLaunch != null) onLaunch.accept(p); });
    }

    // ── Public API ──────────────────────────────────────────

    public void setOnLaunch(Consumer<SwissKitJPlugin> handler) { this.onLaunch = handler; }
    public void setOnBack(Runnable handler) { this.onBack = handler; }

    /** Bind plugin list, auto-refresh on add/remove */
    public void setPlugins(ObservableList<SwissKitJPlugin> list) {
        this.plugins = list;
        list.addListener((ListChangeListener<SwissKitJPlugin>) c -> refresh());
        refresh();
    }

    /** Switch category display */
    public void showCategory(String categoryId) {
        currentCategory = categoryId;
        searchField.clear();
        currentQuery = "";
        setTopMode(false, null);
        crossFadeTo(scrollPane);
        refresh();
        animateGridIn();
    }

    /** Switch to custom page (e.g. settings, plugin market) */
    public void showPage(Node page, String title) {
        pageScrollPane.setContent(page);
        setTopMode(true, title);
        crossFadeTo(pageScrollPane);
        detailPanel.hide();
    }

    /** Back to tool grid home */
    public void showToolGrid() {
        setTopMode(false, null);
        crossFadeTo(scrollPane);
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

        // Center: StackPane (switchable content) + Detail panel
        pageStack.getChildren().add(scrollPane);
        pageStack.setAlignment(Pos.TOP_LEFT);

        HBox center = new HBox(pageStack, detailPanel);
        HBox.setHgrow(pageStack, Priority.ALWAYS);
        setCenter(center);
    }

    private HBox buildBackBar() {
        Label backBtn = new Label("← 返回");
        backBtn.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.70); -fx-font-size: 13px;" +
            "-fx-cursor: hand; -fx-padding: 4 10 4 0;"
        );
        backBtn.setOnMouseEntered(e ->
            backBtn.setStyle("-fx-text-fill: rgba(255,255,255,1); -fx-font-size: 13px;" +
                             "-fx-cursor: hand; -fx-padding: 4 10 4 0;")
        );
        backBtn.setOnMouseExited(e ->
            backBtn.setStyle("-fx-text-fill: rgba(255,255,255,0.70); -fx-font-size: 13px;" +
                             "-fx-cursor: hand; -fx-padding: 4 10 4 0;")
        );
        backBtn.setOnMouseClicked(e -> {
            showToolGrid();
            if (onBack != null) onBack.run();
        });

        Label sep = new Label("/");
        sep.setStyle("-fx-text-fill: rgba(255,255,255,0.25); -fx-font-size: 13px; -fx-padding: 4 6 4 0;");

        Label titleLabel = new Label();
        titleLabel.getStyleClass().add("back-title");
        titleLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.90); -fx-font-size: 13px; -fx-font-weight: bold;");

        HBox bar = new HBox(6, backBtn, sep, titleLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPrefHeight(38);
        return bar;
    }

    private HBox buildSearchBar() {
        Label searchIcon = new Label("🔍");
        searchIcon.setStyle("-fx-font-size: 13px; -fx-text-fill: rgba(255,255,255,0.28);");

        searchField.getStyleClass().add("search-field");
        searchField.setPromptText("Search tools...");
        searchField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            currentQuery = newVal.trim().toLowerCase();
            refresh();
        });

        Label kbdHint = new Label("⌘K");
        kbdHint.setStyle(
            "-fx-font-size: 10px; -fx-text-fill: rgba(255,255,255,0.28);" +
            "-fx-background-color: rgba(255,255,255,0.06);" +
            "-fx-border-color: rgba(255,255,255,0.10); -fx-border-width: 1;" +
            "-fx-border-radius: 4; -fx-background-radius: 4;" +
            "-fx-padding: 1 5 1 5; -fx-font-family: 'SF Mono','Consolas',monospace;"
        );

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
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sp.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        return sp;
    }

    private ScrollPane buildScrollPane() {
        // Tool grid
        toolGrid.setHgap(10);
        toolGrid.setVgap(10);
        toolGrid.setPadding(new Insets(16));
        toolGrid.setPrefWrapLength(600);

        VBox wrapper = new VBox(
            sectionHeader("FREQUENT", ""),
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
        return sp;
    }

    // ── Grid refresh ──────────────────────────────────────────

    private void refresh() {
        if (plugins == null) return;

        List<SwissKitJPlugin> filtered = plugins.stream()
            .filter(this::matchesCategory)
            .filter(this::matchesQuery)
            .toList();

        toolGrid.getChildren().clear();

        for (int i = 0; i < filtered.size(); i++) {
            SwissKitJPlugin p = filtered.get(i);
            ToolCard card = new ToolCard(p, this::onCardSelect);
            card.setPrefWidth(152);
            card.setPrefHeight(130);

            // Staggered entry delay
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

            toolGrid.getChildren().add(card);
        }

        // Empty state message
        if (filtered.isEmpty()) {
            Label empty = new Label("No matching tools found");
            empty.setStyle("-fx-text-fill: rgba(255,255,255,0.28); -fx-font-size: 13px;");
            empty.setPadding(new Insets(40, 0, 0, 0));
            toolGrid.getChildren().add(empty);
        }
    }

    // ── Filter logic ──────────────────────────────────────────

    private boolean matchesCategory(SwissKitJPlugin p) {
        return switch (currentCategory) {
            case "all"     -> true;
            case "plugins" -> !"builtin".equals(p.getType());
            case "fav"     -> false; // TODO: integrate favorites persistence
            default        -> p.getCategory().equalsIgnoreCase(currentCategory);
        };
    }

    private boolean matchesQuery(SwissKitJPlugin p) {
        if (currentQuery.isEmpty()) return true;
        return p.getName().toLowerCase().contains(currentQuery)
            || p.getDescription().toLowerCase().contains(currentQuery);
    }

    // ── Card selection ──────────────────────────────────────────

    private void onCardSelect(SwissKitJPlugin plugin) {
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

    private HBox sectionHeader(String title, String action) {
        Label titleLabel = new Label(title.toUpperCase());
        titleLabel.getStyleClass().add("section-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(titleLabel, spacer);
        if (!action.isEmpty()) {
            Label actionLabel = new Label(action);
            actionLabel.setStyle("-fx-text-fill: #5b8cf7; -fx-font-size: 12px; -fx-cursor: hand;");
            row.getChildren().add(actionLabel);
        }
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 0, 10, 0));
        return row;
    }
}
