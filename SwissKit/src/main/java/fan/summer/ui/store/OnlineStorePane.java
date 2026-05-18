package fan.summer.ui.store;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Online plugin store pane — fetches plugin list from a remote JSON API
 * and allows one-click installation of JAR files.
 */
public class OnlineStorePane extends VBox {

    private static final Logger log = LoggerFactory.getLogger(OnlineStorePane.class);

    private final Runnable onInstallComplete;
    private final VBox pluginListContainer;
    private final ProgressBar fetchProgress;
    private final Label statusLabel;
    private final ScrollPane scrollPane;
    private final HBox loadingRow;

    public OnlineStorePane(Runnable onInstallComplete) {
        this.onInstallComplete = onInstallComplete;
        setSpacing(20);
        setStyle("-fx-background-color: transparent;");
        setPadding(new Insets(24));

        // Title + description
        Label title = new Label("Plugin Store");
        title.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.90);" +
            "-fx-font-size: 18px; -fx-font-weight: 500;"
        );

        Label desc = new Label(
            "Browse and install plugins from the online store."
        );
        desc.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.45);" +
            "-fx-font-size: 12px;"
        );
        desc.setWrapText(true);
        desc.maxWidthProperty().bind(
            widthProperty().subtract(48)  // 24px padding on each side
        );

        // Refresh button
        Button refreshBtn = glassBtn("↻ Refresh", false);
        refreshBtn.setOnAction(e -> fetchPluginList());

        // Plugin list scroll area
        pluginListContainer = new VBox(12);
        pluginListContainer.setStyle("-fx-background-color: transparent;");

        scrollPane = new ScrollPane(pluginListContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(400);
        scrollPane.setStyle(
            "-fx-background-color: transparent;" +
            "-fx-border-color: transparent;" +
            "-fx-background: transparent;"
        );

        // Loading row
        Label spinner = new Label("⏳");
        spinner.setStyle("-fx-font-size: 16px;");
        Label loadingText = new Label("Fetching plugin list...");
        loadingText.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px;");
        fetchProgress = new ProgressBar();
        fetchProgress.setPrefWidth(200);
        fetchProgress.setStyle("-fx-accent: #5b8cf7;");
        loadingRow = new HBox(10, spinner, loadingText, fetchProgress);
        loadingRow.setAlignment(Pos.CENTER_LEFT);
        loadingRow.setVisible(false);
        loadingRow.setPadding(new Insets(20));

        // Status label
        statusLabel = new Label();
        statusLabel.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.50);" +
            "-fx-font-size: 12px;"
        );
        statusLabel.setWrapText(true);

        getChildren().addAll(title, desc, refreshBtn, scrollPane, loadingRow, statusLabel);

        // Auto-fetch on creation
        fetchPluginList();
    }

    private void fetchPluginList() {
        String urlStr = fan.summer.ui.setting.SwissKitJSettingUi.getStoreUrl();
        showLoading(true);
        statusLabel.setText("");

        new Thread(() -> {
            try {
                List<StorePlugin> plugins = fetchPlugins(urlStr);
                Platform.runLater(() -> {
                    showLoading(false);
                    displayPlugins(plugins);
                });
            } catch (Exception e) {
                log.error("Failed to fetch plugin list from {}", urlStr, e);
                Platform.runLater(() -> {
                    showLoading(false);
                    showError("Failed to fetch: " + e.getMessage());
                    displayEmptyState();
                });
            }
        }).start();
    }

    private List<StorePlugin> fetchPlugins(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
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
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }

        return parsePluginJson(sb.toString());
    }

    private List<StorePlugin> parsePluginJson(String json) {
        List<StorePlugin> result = new ArrayList<>();
        // Simple JSON parser for the plugin list array
        // Expected format: [{ "id": "...", "name": "...", "description": "...", "version": "...", "jarUrl": "...", "iconStyle": "...", "category": "..." }, ...]
        try {
            // Find array start
            int arrayStart = json.indexOf('[');
            int arrayEnd = json.lastIndexOf(']');
            if (arrayStart == -1 || arrayEnd == -1) return result;

            String arrayContent = json.substring(arrayStart + 1, arrayEnd);
            // Split by }{ (avoiding full regex for safety)
            String[] objects = arrayContent.split("\\},\\s*\\{");

            for (String obj : objects) {
                if (!obj.startsWith("{")) obj = "{" + obj;
                if (!obj.endsWith("}")) obj = obj + "}";

                StorePlugin p = new StorePlugin();
                p.id = extractJsonString(obj, "id");
                p.name = extractJsonString(obj, "name");
                p.description = extractJsonString(obj, "description");
                p.version = extractJsonString(obj, "version");
                p.jarUrl = extractJsonString(obj, "jarUrl");
                p.iconStyle = extractJsonString(obj, "iconStyle", "ic-blue");
                p.category = extractJsonString(obj, "category", "other");

                if (p.id != null && p.name != null && p.jarUrl != null) {
                    result.add(p);
                }
            }
        } catch (Exception e) {
            log.warn("JSON parse error, showing partial results", e);
        }
        return result;
    }

    private String extractJsonString(String json, String key) {
        return extractJsonString(json, key, null);
    }

    private String extractJsonString(String json, String key, String defaultVal) {
        String search = "\"" + key + "\"";
        int keyPos = json.indexOf(search);
        if (keyPos == -1) return defaultVal;

        int colonPos = json.indexOf(':', keyPos);
        if (colonPos == -1) return defaultVal;

        int valueStart = json.indexOf('"', colonPos + 1);
        if (valueStart == -1) return defaultVal;

        int valueEnd = json.indexOf('"', valueStart + 1);
        if (valueEnd == -1) return defaultVal;

        return json.substring(valueStart + 1, valueEnd);
    }

    private void displayPlugins(List<StorePlugin> plugins) {
        pluginListContainer.getChildren().clear();

        if (plugins.isEmpty()) {
            displayEmptyState();
            return;
        }

        Label countLabel = new Label(plugins.size() + " plugin(s) available");
        countLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px; -fx-font-weight: 500;");
        pluginListContainer.getChildren().add(countLabel);

        for (StorePlugin plugin : plugins) {
            VBox card = buildPluginCard(plugin);
            pluginListContainer.getChildren().add(card);
        }

        statusLabel.setText("Found " + plugins.size() + " plugin(s)");
        statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.45); -fx-font-size: 12px;");
    }

    private VBox buildPluginCard(StorePlugin plugin) {
        VBox card = new VBox();
        card.setSpacing(8);
        card.setPadding(new Insets(16));
        card.setStyle(
            "-fx-background-color: rgba(255,255,255,0.04);" +
            "-fx-border-color: rgba(255,255,255,0.10);" +
            "-fx-border-width: 1; -fx-border-radius: 10;" +
            "-fx-background-radius: 10;"
        );

        // Header: name + version
        HBox header = new HBox();
        Label nameLabel = new Label(plugin.name);
        nameLabel.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.90);" +
            "-fx-font-size: 14px; -fx-font-weight: 500;"
        );
        Label versionBadge = new Label("v" + plugin.version);
        versionBadge.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.40);" +
            "-fx-font-size: 11px; -fx-background-color: rgba(255,255,255,0.07);" +
            "-fx-background-radius: 4; -fx-padding: 2 6 2 6;"
        );

        Label categoryBadge = new Label(plugin.category);
        categoryBadge.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.35);" +
            "-fx-font-size: 10px; -fx-background-color: rgba(255,255,255,0.05);" +
            "-fx-background-radius: 4; -fx-padding: 2 6 2 6;"
        );

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        header.getChildren().addAll(nameLabel, versionBadge, categoryBadge, spacer);
        header.setAlignment(Pos.CENTER_LEFT);

        // Description
        Label descLabel = new Label(plugin.description);
        descLabel.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.50);" +
            "-fx-font-size: 12px;"
        );
        descLabel.setWrapText(true);

        // ID
        Label idLabel = new Label(plugin.id);
        idLabel.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.25);" +
            "-fx-font-size: 10px; -fx-font-family: 'SF Mono','Consolas',monospace;"
        );

        // Action: Install button + progress
        ProgressBar installProgress = new ProgressBar();
        installProgress.setPrefWidth(Double.MAX_VALUE);
        installProgress.setStyle("-fx-accent: #4cd97b;");
        installProgress.setVisible(false);

        Button installBtn = glassBtn("Install", true);
        installBtn.setOnAction(e -> installPlugin(plugin, installBtn, installProgress));

        HBox actionRow = new HBox(10, installBtn, installProgress);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        card.getChildren().addAll(header, descLabel, idLabel, actionRow);

        return card;
    }

    private void installPlugin(StorePlugin plugin, Button installBtn, ProgressBar progress) {
        installBtn.setDisable(true);
        progress.setVisible(true);
        progress.setProgress(-1);
        final ProgressBar finalProgress = progress;

        new Thread(() -> {
            try {
                Path pluginDir = Path.of(System.getProperty("user.dir"), "plugins");
                Files.createDirectories(pluginDir);

                // Download JAR
                HttpURLConnection conn = (HttpURLConnection) new URL(plugin.jarUrl).openConnection();
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

                // Stream to file
                try (var in = conn.getInputStream();
                     var out = new FileOutputStream(target.toFile())) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long total = 0;
                    long contentLength = conn.getContentLengthLong();
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        total += bytesRead;
                        if (contentLength > 0) {
                            double progressVal = (double) total / contentLength;
                            Platform.runLater(() -> finalProgress.setProgress(progressVal));
                        }
                    }
                }

                log.info("Plugin downloaded and installed: {}", target);

                Platform.runLater(() -> {
                    progress.setProgress(1.0);
                    statusLabel.setText("✓ Installed: " + plugin.name + " → " + jarFileName);
                    statusLabel.setStyle("-fx-text-fill: #4cd97b; -fx-font-size: 12px;");
                    installBtn.setDisable(false);
                    if (onInstallComplete != null) onInstallComplete.run();
                });
            } catch (Exception ex) {
                log.error("Plugin install failed for {}", plugin.id, ex);
                Platform.runLater(() -> {
                    progress.setVisible(false);
                    showError("Install failed: " + ex.getMessage());
                    installBtn.setDisable(false);
                });
            }
        }).start();
    }

    private void displayEmptyState() {
        pluginListContainer.getChildren().clear();
        Label empty = new Label("No plugins available or store unreachable.");
        empty.setStyle(
            "-fx-text-fill: rgba(255,255,255,0.30);" +
            "-fx-font-size: 13px;"
        );
        empty.setPadding(new Insets(40));
        empty.setAlignment(Pos.CENTER);
        pluginListContainer.getChildren().add(empty);
    }

    private void showLoading(boolean show) {
        loadingRow.setVisible(show);
        fetchProgress.setVisible(show);
    }

    private void showError(String msg) {
        statusLabel.setText("❌ " + msg);
        statusLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
    }

    private static Button glassBtn(String text, boolean primary) {
        Button btn = new Button(text);
        if (primary) {
            btn.setStyle(
                "-fx-background-color: #5b8cf7; -fx-text-fill: white; -fx-font-size: 13px;" +
                "-fx-font-weight: 500; -fx-background-radius: 8; -fx-border-width: 0;" +
                "-fx-padding: 9 18 9 18; -fx-cursor: hand;"
            );
        } else {
            btn.setStyle(
                "-fx-background-color: rgba(255,255,255,0.07);" +
                "-fx-border-color: rgba(255,255,255,0.12); -fx-border-width: 1;" +
                "-fx-text-fill: rgba(255,255,255,0.75); -fx-font-size: 13px;" +
                "-fx-background-radius: 8; -fx-border-radius: 8;" +
                "-fx-padding: 9 18 9 18; -fx-cursor: hand;"
            );
        }
        return btn;
    }

    // ── Data model ────────────────────────────────────────────────

    public static class StorePlugin {
        public String id;
        public String name;
        public String description;
        public String version;
        public String jarUrl;
        public String iconStyle;
        public String category;
    }
}