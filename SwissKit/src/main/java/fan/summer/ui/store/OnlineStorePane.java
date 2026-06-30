package fan.summer.ui.store;

import fan.summer.api.IconStyle;
import fan.summer.api.ToolCategory;
import fan.summer.api.component.GlassNotification;
import fan.summer.api.i18n.I18n;
import fan.summer.plugin.PluginLoader;
import fan.summer.ui.store.StorePluginLogic.InstallState;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fan.summer.ai.util.JsonHelper;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Online plugin store pane: fetches the plugin catalog from a remote JSON API and
 * displays each plugin as a card in a responsive grid. A search box and category
 * dropdown filter the visible cards in memory; each card shows a tri-state install
 * button (install / installed / update) computed against the locally installed set.
 * <p>
 * The catalog is fetched automatically on construction. Downloads are written to a
 * {@code .part} temp file and atomically moved into the {@code plugins/} directory.
 *
 * @see PluginLoader#resolvePluginsDir()
 * @see StorePluginLogic
 * @since 1.0
 */
public class OnlineStorePane extends VBox {

    private static final Logger log = LoggerFactory.getLogger(OnlineStorePane.class);

    private static final double CARD_WIDTH = 260;

    private final Runnable onInstallComplete;
    /** Installed plugin id → version; used to compute install state. Never null. */
    private final Map<String, String> installedVersions;

    private final TextField searchField;
    private final ComboBox<ToolCategory> categoryBox;
    private final FlowPane grid;
    private final ProgressBar fetchProgress;
    private final Label statusLabel;
    private final HBox loadingRow;

    /** Full catalog from the last successful fetch; filtered in memory. */
    private final List<StorePlugin> allPlugins = new ArrayList<>();

    /** Sentinel meaning "All categories" in the dropdown (modelled as null value). */
    private static final ToolCategory ALL = null;

    /**
     * Backwards-compatible constructor with no installed-version info: every plugin
     * shows the plain "Install" button.
     *
     * @param onInstallComplete callback invoked after each successful install; may be null
     */
    public OnlineStorePane(Runnable onInstallComplete) {
        this(onInstallComplete, null);
    }

    /**
     * Constructs the online store pane, auto-fetching the plugin list on creation.
     *
     * @param onInstallComplete callback invoked after each successful install; may be null
     * @param installedVersions installed plugin id → version map; may be null
     */
    public OnlineStorePane(Runnable onInstallComplete, Map<String, String> installedVersions) {
        this.onInstallComplete = onInstallComplete;
        this.installedVersions = installedVersions != null
                ? new HashMap<>(installedVersions) : new HashMap<>();

        setSpacing(16);
        setStyle("-fx-background-color: transparent;");
        setPadding(new Insets(24));

        // ── Title + description ──────────────────────────────
        Label title = new Label(I18n.get("store.online.title"));
        title.setStyle("-fx-text-fill: rgba(255,255,255,0.90); -fx-font-size: 18px; -fx-font-weight: 500;");
        Label desc = new Label(I18n.get("store.online.desc"));
        desc.setStyle("-fx-text-fill: rgba(255,255,255,0.45); -fx-font-size: 12px;");
        desc.setWrapText(true);
        desc.setMaxWidth(Double.MAX_VALUE);

        // ── Toolbar: search + category + refresh ─────────────
        searchField = new TextField();
        searchField.setPromptText(I18n.get("store.online.search"));
        searchField.getStyleClass().add("store-search");
        searchField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((obs, o, n) -> applyFilters());

        categoryBox = new ComboBox<>();
        categoryBox.getItems().add(ALL); // "All"
        categoryBox.getItems().addAll(ToolCategory.values());
        categoryBox.getSelectionModel().select(ALL);
        categoryBox.setConverter(new StringConverter<>() {
            @Override public String toString(ToolCategory c) {
                if (c == ALL) return I18n.get("store.online.category.all");
                return I18n.get("store.online.category." + c.getId());
            }
            @Override public ToolCategory fromString(String s) { return null; }
        });
        categoryBox.getStyleClass().add("sk-combo");
        categoryBox.valueProperty().addListener((obs, o, n) -> applyFilters());

        Button refreshBtn = glassBtn(I18n.get("store.online.refresh"));
        refreshBtn.setOnAction(e -> fetchPluginList());

        HBox toolbar = new HBox(10, searchField, categoryBox, refreshBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // ── Grid in scroll area ──────────────────────────────
        grid = new FlowPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setStyle("-fx-background-color: transparent;");
        grid.setPadding(new Insets(4, 0, 4, 0));

        ScrollPane scrollPane = new ScrollPane(grid);
        scrollPane.setFitToWidth(true);
        scrollPane.setMaxWidth(Double.MAX_VALUE);
        scrollPane.setMaxHeight(Double.MAX_VALUE);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        // ── Loading row ──────────────────────────────────────
        Label spinner = new Label("⏳");
        spinner.setStyle("-fx-font-size: 16px;");
        Label loadingText = new Label(I18n.get("store.online.fetching"));
        loadingText.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px;");
        fetchProgress = new ProgressBar();
        fetchProgress.setPrefWidth(200);
        fetchProgress.setStyle("-fx-accent: #5b8cf7;");
        loadingRow = new HBox(10, spinner, loadingText, fetchProgress);
        loadingRow.setAlignment(Pos.CENTER_LEFT);
        loadingRow.setVisible(false);
        loadingRow.setManaged(false);
        loadingRow.setPadding(new Insets(8, 0, 0, 0));

        // ── Status label ─────────────────────────────────────
        statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.50); -fx-font-size: 12px;");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        getChildren().addAll(title, desc, toolbar, scrollPane, loadingRow, statusLabel);

        fetchPluginList();
    }

    // ── Fetch ────────────────────────────────────────────────────

    private void fetchPluginList() {
        String urlStr = fan.summer.ui.setting.SwissKitJSettingUi.getStoreUrl();
        showLoading(true);
        statusLabel.setText("");

        Thread fetchThread = new Thread(() -> {
            try {
                List<StorePlugin> plugins = fetchPlugins(urlStr);
                Platform.runLater(() -> {
                    showLoading(false);
                    allPlugins.clear();
                    allPlugins.addAll(plugins);
                    applyFilters();
                });
            } catch (Exception e) {
                log.error("Failed to fetch plugin list from {}", urlStr, e);
                Platform.runLater(() -> {
                    showLoading(false);
                    allPlugins.clear();
                    showError(I18n.get("store.online.installFailed", e.getMessage()));
                    applyFilters();
                });
            }
        });
        fetchThread.setName("store-fetch");
        fetchThread.setDaemon(true);
        fetchThread.start();
    }

    private List<StorePlugin> fetchPlugins(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                throw new RuntimeException("HTTP " + responseCode);
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return parsePluginJson(sb.toString());
        } finally {
            conn.disconnect();
        }
    }

    @SuppressWarnings("unchecked")
    private List<StorePlugin> parsePluginJson(String json) {
        List<StorePlugin> result = new ArrayList<>();
        try {
            List<Object> array = JsonHelper.parseList(json);
            if (array == null) return result;
            for (Object item : array) {
                if (!(item instanceof Map<?, ?> rawObj)) continue;
                Map<String, Object> obj = (Map<String, Object>) rawObj;

                StorePlugin p = new StorePlugin();
                p.id = (String) obj.getOrDefault("id", null);
                p.name = (String) obj.getOrDefault("name", null);
                p.description = (String) obj.getOrDefault("description", null);
                p.version = (String) obj.getOrDefault("version", null);
                p.jarUrl = (String) obj.getOrDefault("jarUrl", null);
                p.iconStyle = IconStyle.fromCssClass((String) obj.getOrDefault("iconStyle", "ic-blue"));
                p.category = ToolCategory.fromId((String) obj.getOrDefault("category", "other"));

                if (p.id != null && p.name != null && p.jarUrl != null) result.add(p);
            }
        } catch (Exception e) {
            log.warn("JSON parse error, showing partial results", e);
        }
        return result;
    }

    // ── Filtering + rendering ────────────────────────────────────

    /** Re-applies the current search + category filter and rebuilds the grid. */
    private void applyFilters() {
        String query = searchField.getText();
        ToolCategory filter = categoryBox.getValue();

        grid.getChildren().clear();
        List<StorePlugin> visible = new ArrayList<>();
        for (StorePlugin p : allPlugins) {
            if (StorePluginLogic.matches(p, query, filter)) visible.add(p);
        }

        if (allPlugins.isEmpty()) {
            statusLabel.setText(I18n.get("store.online.noPlugins"));
            statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.40); -fx-font-size: 12px;");
            return;
        }
        if (visible.isEmpty()) {
            statusLabel.setText(I18n.get("store.online.noMatch"));
            statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.40); -fx-font-size: 12px;");
            return;
        }

        for (StorePlugin p : visible) grid.getChildren().add(buildPluginCard(p));

        int installedCount = 0;
        for (StorePlugin p : allPlugins) {
            if (installedVersions.containsKey(p.id)) installedCount++;
        }
        statusLabel.setText(I18n.get("store.online.countWithInstalled", allPlugins.size(), installedCount));
        statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.45); -fx-font-size: 12px;");
    }

    private VBox buildPluginCard(StorePlugin plugin) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(14));
        card.setPrefWidth(CARD_WIDTH);
        card.setMinWidth(CARD_WIDTH);
        card.setMaxWidth(CARD_WIDTH);
        card.getStyleClass().add("store-card");

        // Header: icon tile + name + meta
        StackPane iconTile = buildIconTile(plugin);
        Label nameLabel = new Label(plugin.name);
        nameLabel.getStyleClass().add("store-card-name");
        nameLabel.setWrapText(true);
        nameLabel.setMaxWidth(Double.MAX_VALUE);

        Label versionBadge = new Label("v" + (plugin.version != null ? plugin.version : "?"));
        versionBadge.getStyleClass().add("store-badge");
        Label categoryBadge = new Label(I18n.get("store.online.category." + plugin.category.getId()));
        categoryBadge.getStyleClass().add("store-badge");
        HBox meta = new HBox(6, versionBadge, categoryBadge);
        meta.setAlignment(Pos.CENTER_LEFT);

        VBox titleCol = new VBox(4, nameLabel, meta);
        titleCol.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleCol, Priority.ALWAYS);

        HBox header = new HBox(11, iconTile, titleCol);
        header.setAlignment(Pos.CENTER_LEFT);

        // Description
        Label descLabel = new Label(plugin.description != null ? plugin.description : "");
        descLabel.getStyleClass().add("store-card-desc");
        descLabel.setWrapText(true);
        descLabel.setMaxWidth(Double.MAX_VALUE);
        descLabel.setMinHeight(32);
        descLabel.setMaxHeight(32);

        // Install button (tri-state)
        Button installBtn = new Button();
        installBtn.getStyleClass().add("store-install-btn");
        installBtn.setMaxWidth(Double.MAX_VALUE);
        applyInstallState(installBtn, plugin);

        card.getChildren().addAll(header, descLabel, installBtn);
        return card;
    }

    /** Sets the install button's text/style/handler based on current install state. */
    private void applyInstallState(Button installBtn, StorePlugin plugin) {
        installBtn.getStyleClass().removeAll("installed", "update");
        InstallState state = StorePluginLogic.installState(plugin.id, plugin.version, installedVersions);
        switch (state) {
            case INSTALLED -> {
                installBtn.setText(I18n.get("store.online.btn.installed"));
                installBtn.getStyleClass().add("installed");
                installBtn.setDisable(true);
                installBtn.setOnAction(null);
            }
            case UPDATABLE -> {
                installBtn.setText(I18n.get("store.online.btn.update"));
                installBtn.getStyleClass().add("update");
                installBtn.setDisable(false);
                installBtn.setOnAction(e -> installPlugin(plugin, installBtn));
            }
            case NOT_INSTALLED -> {
                installBtn.setText(I18n.get("store.online.btn.install"));
                installBtn.setDisable(false);
                installBtn.setOnAction(e -> installPlugin(plugin, installBtn));
            }
        }
    }

    private StackPane buildIconTile(StorePlugin plugin) {
        String glyph = (plugin.name != null && !plugin.name.isBlank())
                ? plugin.name.substring(0, 1).toUpperCase() : "?";
        Label letter = new Label(glyph);
        letter.setStyle("-fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold;");
        StackPane tile = new StackPane(letter);
        tile.setMinSize(38, 38);
        tile.setPrefSize(38, 38);
        tile.setMaxSize(38, 38);
        Color c = plugin.iconStyle != null ? plugin.iconStyle.getColor() : IconStyle.BLUE.getColor();
        tile.setStyle(
            "-fx-background-color: " + toRgbCss(c) + ";" +
            "-fx-background-radius: 10;"
        );
        return tile;
    }

    private static String toRgbCss(Color c) {
        return String.format("rgb(%d,%d,%d)",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }

    // ── Install (download + atomic move; unchanged behaviour) ─────

    private void installPlugin(StorePlugin plugin, Button installBtn) {
        installBtn.setDisable(true);
        installBtn.setText(I18n.get("store.online.fetching"));

        Thread installThread = new Thread(() -> {
            Path tempFile = null;
            try {
                Path pluginDir = PluginLoader.resolvePluginsDir();
                Files.createDirectories(pluginDir);

                HttpURLConnection conn = (HttpURLConnection) new URL(plugin.jarUrl).openConnection();
                try {
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);

                    int responseCode = conn.getResponseCode();
                    if (responseCode != 200) {
                        throw new RuntimeException("Download failed: HTTP " + responseCode);
                    }

                    final String jarFileName;
                    String extractedName = plugin.jarUrl.substring(plugin.jarUrl.lastIndexOf('/') + 1);
                    if (extractedName.toLowerCase().endsWith(".jar")) {
                        jarFileName = extractedName;
                    } else {
                        jarFileName = plugin.id.replace('.', '-') + ".jar";
                    }
                    Path target = pluginDir.resolve(jarFileName);

                    // Write to a temporary .part file first, then atomically move to target.
                    tempFile = pluginDir.resolve(jarFileName + ".part");
                    try (var in = conn.getInputStream();
                         var out = new FileOutputStream(tempFile.toFile())) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    }

                    Files.move(tempFile, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    tempFile = null;

                    log.info("Plugin downloaded and installed: {}", target);

                    Platform.runLater(() -> {
                        // Mark installed at the store version and refresh this card's button.
                        installedVersions.put(plugin.id, plugin.version);
                        applyInstallState(installBtn, plugin);
                        statusLabel.setText(I18n.get("store.online.installed", plugin.name, jarFileName));
                        statusLabel.setStyle("-fx-text-fill: #4cd97b; -fx-font-size: 12px;");
                        GlassNotification.toast(OnlineStorePane.this, GlassNotification.Type.SUCCESS,
                                I18n.get("store.online.installed", plugin.name, jarFileName));
                        if (onInstallComplete != null) onInstallComplete.run();
                    });
                } finally {
                    conn.disconnect();
                }
            } catch (Exception ex) {
                if (tempFile != null) {
                    try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
                }
                log.error("Plugin install failed for {}", plugin.id, ex);
                Platform.runLater(() -> {
                    showError(I18n.get("store.online.installFailed", ex.getMessage()));
                    applyInstallState(installBtn, plugin); // restore install/update button
                });
            }
        });
        installThread.setName("store-install");
        installThread.setDaemon(true);
        installThread.start();
    }

    // ── Small helpers ─────────────────────────────────────────────

    private void showLoading(boolean show) {
        loadingRow.setVisible(show);
        loadingRow.setManaged(show);
        fetchProgress.setVisible(show);
        if (show) fetchProgress.setProgress(-1);
    }

    private void showError(String msg) {
        statusLabel.setText("❌ " + msg);
        statusLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
    }

    private static Button glassBtn(String text) {
        Button btn = new Button(text);
        btn.setStyle(
            "-fx-background-color: rgba(255,255,255,0.07);" +
            "-fx-border-color: rgba(255,255,255,0.12); -fx-border-width: 1;" +
            "-fx-text-fill: rgba(255,255,255,0.75); -fx-font-size: 13px;" +
            "-fx-background-radius: 8; -fx-border-radius: 8;" +
            "-fx-padding: 8 14 8 14; -fx-cursor: hand;"
        );
        return btn;
    }
}
