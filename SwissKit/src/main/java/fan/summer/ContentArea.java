package fan.summer;

import javafx.animation.*;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.util.List;
import java.util.function.Consumer;
 /* Main content area.
 * Contains: search bar / tool grid / detail panel / page transition animations.
 * Binds plugin data via setPlugins(ObservableList), auto-responds to additions/removals.
 */
public class ContentArea extends BorderPane {

    // ── Sub-components ────────────────────────────────────────────
    private final TextField   searchField  = new TextField();
    private final FlowPane    toolGrid     = new FlowPane();
    private final DetailPanel detailPanel  = new DetailPanel();
    private final StackPane   pageStack    = new StackPane();
    private final ScrollPane  scrollPane;

    // ── State ──────────────────────────────────────────────
    private ObservableList<SwissKitPlugin> plugins;
    private String   currentCategory = "all";
    private String   currentQuery    = "";
    private Consumer<SwissKitPlugin> onLaunch;

    public ContentArea() {
        scrollPane = buildScrollPane();
        buildLayout();
        detailPanel.setOnLaunch(p -> { if (onLaunch != null) onLaunch.accept(p); });
    }

    // ── Public API ──────────────────────────────────────────

    public void setOnLaunch(Consumer<SwissKitPlugin> handler) { this.onLaunch = handler; }

    /** Bind plugin list; auto-refreshes on add/remove */
    public void setPlugins(ObservableList<SwissKitPlugin> list) {
        this.plugins = list;
        list.addListener((ListChangeListener<SwissKitPlugin>) c -> refresh());
        refresh();
    }

    /** Switch display category */
    public void showCategory(String categoryId) {
        currentCategory = categoryId;
        searchField.clear();
        currentQuery = "";
        refresh();
        animateGridIn();
    }

    /** Switch to custom page (e.g., settings, plugin store) */
    public void showPage(Node page, String title) {
        crossFadeTo(page);
        detailPanel.hide();
    }

    /** Return to tool grid main page */
    public void showToolGrid() {
        crossFadeTo(scrollPane);
    }

    // ── Layout Build ──────────────────────────────────────────

    private void buildLayout() {
        // Search bar
        HBox searchBar = buildSearchBar();
        VBox top = new VBox(searchBar);
        top.setPadding(new Insets(12, 16, 0, 16));
        setTop(top);

        // Center: StackPane (switchable content) + Detail panel
        pageStack.getChildren().add(scrollPane);
        pageStack.setAlignment(Pos.TOP_LEFT);

        HBox center = new HBox(pageStack, detailPanel);
        HBox.setHgrow(pageStack, Priority.ALWAYS);
        setCenter(center);
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

    private ScrollPane buildScrollPane() {
        // Tool grid
        toolGrid.setHgap(10);
        toolGrid.setVgap(10);
        toolGrid.setPadding(new Insets(16));
        toolGrid.setPrefWrapLength(600);

        VBox wrapper = new VBox(
            sectionHeader("Tools", ""),
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

    // ── Grid Refresh ──────────────────────────────────────────

    private void refresh() {
        if (plugins == null) return;

        List<SwissKitPlugin> filtered = plugins.stream()
            .filter(this::matchesCategory)
            .filter(this::matchesQuery)
            .toList();

        toolGrid.getChildren().clear();

        for (int i = 0; i < filtered.size(); i++) {
            SwissKitPlugin p = filtered.get(i);
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

    // ── Filter Logic ──────────────────────────────────────────

    private boolean matchesCategory(SwissKitPlugin p) {
        return switch (currentCategory) {
            case "all"     -> true;
            case "plugins" -> !"builtin".equals(p.getType());
            case "fav"     -> false; // TODO: integrate favorites persistence
            default        -> p.getCategory().equalsIgnoreCase(currentCategory);
        };
    }

    private boolean matchesQuery(SwissKitPlugin p) {
        if (currentQuery.isEmpty()) return true;
        return p.getName().toLowerCase().contains(currentQuery)
            || p.getDescription().toLowerCase().contains(currentQuery);
    }

    // ── Card Selection ──────────────────────────────────────────

    private void onCardSelect(SwissKitPlugin plugin) {
        detailPanel.show(plugin);
    }

    // ── Page Transition Animation (Cross-fade) ──────────────────────

    private void crossFadeTo(Node next) {
        Node current = pageStack.getChildren().isEmpty()
            ? null : pageStack.getChildren().get(0);

        if (current == next) return;

        next.setOpacity(0);
        if (!pageStack.getChildren().contains(next))
            pageStack.getChildren().add(next);
        next.toFront();

        FadeTransition fadeIn = new FadeTransition(Duration.millis(220), next);
        fadeIn.setToValue(1);

        if (current != null) {
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

    // ── Helper Node Factory ──────────────────────────────────────

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