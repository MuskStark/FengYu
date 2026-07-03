package plugin.swisskit.keepawake.ui;

import fan.summer.api.i18n.I18n;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.util.Duration;
import plugin.swisskit.keepawake.service.KeepAwakeService;

/**
 * KeepAwake control panel.
 *
 * <p>Themed exclusively with SwissKitJ {@code -sk-*} design tokens and {@code .sk-*}
 * foundation classes so it blends in as a native host surface in both dark and light
 * themes. No inline hex colors, no custom font family (the host's global font stack
 * applies).
 */
public class KeepAwakeUi {

    /** SwissKitJ design tokens (looked-up colors — resolve per-theme on the scene root). */
    private static final String BG            = "-sk-bg";
    private static final String CARD_BG       = "-sk-bg-elevated";
    private static final String BORDER        = "-sk-border";
    private static final String BORDER_STRONG = "-sk-border-strong";
    private static final String TEXT          = "-sk-text";
    private static final String TEXT_SEC      = "-sk-text-secondary";
    private static final String TEXT_DIM      = "-sk-text-disabled";
    private static final String ACCENT        = "-sk-accent";
    private static final String SUCCESS       = "-sk-success";
    private static final String DANGER        = "-sk-danger";

    private final KeepAwakeService service = KeepAwakeService.getInstance();
    private final VBox root = new VBox();
    private final Label statusValue = new Label();
    private final Label methodValue = new Label();
    private final Label elapsedValue = new Label("00:00:00");
    private final Button startButton = new Button();
    private final Button stopButton = new Button();
    private final HBox dotIndicator = new HBox(4);
    private final MethodToggle methodToggle = new MethodToggle();
    private Timeline timer;
    private Timeline blinkTimer;

    public KeepAwakeUi() {
        initComponents();
    }

    public Node getView() {
        return root;
    }

    /**
     * Stop UI animations only. The service keeps running in the background.
     */
    public void suspendUi() {
        if (timer != null) { timer.stop(); timer = null; }
        if (blinkTimer != null) { blinkTimer.stop(); blinkTimer = null; }
    }

    /**
     * Resume UI animations, syncing with the singleton service state.
     */
    public void resumeUi() {
        refreshDisplay();
        methodToggle.sync();
        if (service.isRunning()) {
            startButton.setDisable(true);
            startButton.setOpacity(0.35);
            stopButton.setDisable(false);
            stopButton.setOpacity(1.0);

            timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> updateElapsed()));
            timer.setCycleCount(Timeline.INDEFINITE);
            timer.play();

            blinkTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> toggleDot()));
            blinkTimer.setCycleCount(Timeline.INDEFINITE);
            blinkTimer.play();
        } else {
            startButton.setDisable(false);
            startButton.setOpacity(1.0);
            stopButton.setDisable(true);
            stopButton.setOpacity(0.35);
        }
    }

    private void initComponents() {
        root.setStyle("-fx-background-color: " + BG + "; -fx-background-radius: 8;");
        root.setPadding(new Insets(20));
        root.setSpacing(0);
        root.setFillWidth(true);

        String p = "plugin.keepawake.";

        // Header
        Label header = new Label("✈  KEEP ALIVE BOARD");
        header.getStyleClass().addAll("sk-accent-text");
        header.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        header.setMaxWidth(Double.MAX_VALUE);
        header.setAlignment(Pos.CENTER);
        header.setPadding(new Insets(0, 0, 12, 0));

        // Status row
        BoardRow statusRow = new BoardRow("STATUS");
        statusRow.setValueNode(statusValue);

        // Method row
        BoardRow methodRow = new BoardRow("METHOD");
        methodRow.setValueNode(methodValue);

        // Elapsed row
        BoardRow elapsedRow = new BoardRow("ELAPSED");
        elapsedRow.setValueNode(elapsedValue);
        elapsedValue.setStyle("-fx-font-size: 18px; -fx-text-fill: " + TEXT + ";");

        // Dot indicator
        dotIndicator.setAlignment(Pos.CENTER_LEFT);
        dotIndicator.setPadding(new Insets(8, 0, 0, 0));

        // Board body
        VBox board = new VBox();
        board.setStyle("-fx-background-color: " + CARD_BG + "; -fx-background-radius: 8;"
                + "-fx-border-color: " + BORDER + "; -fx-border-width: 1; -fx-border-radius: 8;");
        board.setPadding(new Insets(14, 16, 14, 16));
        board.setSpacing(2);
        board.getChildren().addAll(statusRow, divider(), methodRow, divider(), elapsedRow, dotIndicator);

        // Buttons — one primary action, stop is secondary (destructive tone)
        startButton.getStyleClass().add("sk-btn-primary");
        stopButton.getStyleClass().add("sk-btn-secondary");

        I18n.bind(startButton.textProperty(), p + "start");
        I18n.bind(stopButton.textProperty(), p + "stop");

        HBox buttonRow = new HBox(10, startButton, stopButton);
        buttonRow.setAlignment(Pos.CENTER);
        buttonRow.setPadding(new Insets(16, 0, 0, 0));

        // Method toggle switch row
        Label toggleLabel = new Label();
        toggleLabel.getStyleClass().add("sk-t3");
        toggleLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        I18n.bind(toggleLabel.textProperty(), p + "toggleLabel");

        HBox toggleRow = new HBox(12, toggleLabel, methodToggle);
        toggleRow.setAlignment(Pos.CENTER);
        toggleRow.setPadding(new Insets(0, 0, 0, 0));

        VBox controlArea = new VBox(toggleRow, buttonRow);
        controlArea.setAlignment(Pos.CENTER);
        controlArea.setPadding(new Insets(16, 0, 0, 0));

        root.getChildren().addAll(header, board, controlArea);

        startButton.setOnAction(e -> handleStart());
        stopButton.setOnAction(e -> handleStop());

        // Sync with singleton state on first creation
        resumeUi();
        I18n.addListener(this::refreshDisplay);
    }

    private Node divider() {
        Region d = new Region();
        d.setStyle("-fx-background-color: " + BORDER + ";");
        d.setPrefHeight(1);
        d.setPadding(new Insets(4, 0, 4, 0));
        VBox.setMargin(d, new Insets(6, 0, 6, 0));
        return d;
    }

    private void handleStart() {
        service.start();
        resumeUi();
    }

    private void handleStop() {
        stopInternal();
    }

    private void stopInternal() {
        service.stop();
        suspendUi();

        startButton.setDisable(false);
        startButton.setOpacity(1.0);
        stopButton.setDisable(true);
        stopButton.setOpacity(0.35);

        refreshDisplay();
        methodToggle.sync();
    }

    private void updateElapsed() {
        long elapsed = System.currentTimeMillis() - service.getStartTimeMs();
        long s = (elapsed / 1000) % 60;
        long m = (elapsed / 60_000) % 60;
        long h = elapsed / 3_600_000;
        elapsedValue.setText(String.format("%02d:%02d:%02d", h, m, s));
    }

    private void toggleDot() {
        if (dotIndicator.getChildren().isEmpty()) return;
        Label dot = (Label) dotIndicator.getChildren().getFirst();
        boolean on = dot.getStyle().contains(SUCCESS);
        dot.setStyle("-fx-text-fill: " + (on ? CARD_BG : SUCCESS) + "; -fx-font-size: 10px;");
    }

    private void refreshDisplay() {
        String p = "plugin.keepawake.";
        dotIndicator.getChildren().clear();

        if (!service.isRunning()) {
            statusValue.setText(I18n.get(p + "stopped"));
            statusValue.getStyleClass().setAll("sk-danger-text");
            statusValue.setStyle("-fx-font-weight: bold;");
            methodValue.setText(I18n.get(p + "methodNone"));
            methodValue.getStyleClass().setAll("sk-t2");
            methodValue.setStyle("");
            elapsedValue.setText("00:00:00");
            return;
        }

        statusValue.setText(I18n.get(p + "running"));
        statusValue.getStyleClass().setAll("sk-success-text");
        statusValue.setStyle("-fx-font-weight: bold;");

        String methodKey = service.isSystemApi() ? p + "methodSystem" : p + "methodMouse";
        methodValue.setText(I18n.get(methodKey));
        methodValue.getStyleClass().setAll("sk-t1");
        methodValue.setStyle("");

        Label dot = new Label("●");
        dot.setStyle("-fx-text-fill: " + SUCCESS + "; -fx-font-size: 10px;");
        Label dotLabel = new Label(" ACTIVE");
        dotLabel.getStyleClass().add("sk-success-text");
        dotLabel.setStyle("-fx-font-size: 10px;");
        dotIndicator.getChildren().addAll(dot, dotLabel);

        updateElapsed();
    }

    // ── Sliding toggle switch ────────────────────────────────────────────

    /**
     * Pill-style toggle switch: left = Mouse Sim, right = System API.
     * A sliding pill highlights the active option; click to toggle.
     * Disabled while the service is running.
     */
    private class MethodToggle extends StackPane {

        private static final double PAD = 4;
        private static final double OPT_W = 116;
        private static final double TRACK_W = PAD * 2 + OPT_W * 2;
        private static final double TRACK_H = 36;
        private static final double PILL_W = OPT_W;
        private static final double PILL_H = TRACK_H - PAD * 2;

        private final Region pill = new Region();
        private final Label optMouse = new Label();
        private final Label optApi = new Label();
        private final HBox optionsBox = new HBox();

        MethodToggle() {
            String p = "plugin.keepawake.";

            // ── Track ──
            setPrefSize(TRACK_W, TRACK_H);
            setMaxSize(TRACK_W, TRACK_H);
            setStyle("-fx-background-color: " + BORDER + ";"
                    + " -fx-background-radius: 999px;"
                    + " -fx-border-color: " + BORDER_STRONG + ";"
                    + " -fx-border-width: 0.5px;"
                    + " -fx-border-radius: 999px;"
                    + " -fx-padding: " + PAD + "px;"
                    + " -fx-cursor: hand;");

            // ── Pill (slides behind active option) ──
            pill.setPrefSize(PILL_W, PILL_H);
            pill.setMaxSize(PILL_W, PILL_H);
            pill.setStyle("-fx-background-color: " + CARD_BG + ";"
                    + " -fx-background-radius: 999px;"
                    + " -fx-border-color: " + BORDER_STRONG + ";"
                    + " -fx-border-width: 0.5px;"
                    + " -fx-border-radius: 999px;");

            StackPane.setAlignment(pill, Pos.TOP_LEFT);

            // ── Options (z-index above pill) ──
            I18n.bind(optMouse.textProperty(), p + "toggleMouse");
            I18n.bind(optApi.textProperty(), p + "toggleApi");

            optMouse.setPrefSize(OPT_W, PILL_H);
            optApi.setPrefSize(OPT_W, PILL_H);
            optMouse.setAlignment(Pos.CENTER);
            optApi.setAlignment(Pos.CENTER);

            optionsBox.setPrefSize(OPT_W * 2, PILL_H);
            optionsBox.getChildren().addAll(optMouse, optApi);

            getChildren().addAll(pill, optionsBox);

            // Click to toggle
            setOnMouseClicked(e -> {
                if (service.isRunning()) return;
                service.setUseSystemApi(!service.isUseSystemApi());
                animatePill();
            });

            // Initial position
            movePill(false);
            updateOptColors();
        }

        void sync() {
            movePill(true);
            if (service.isRunning()) {
                setCursor(Cursor.DEFAULT);
                setOpacity(0.5);
            } else {
                setCursor(Cursor.HAND);
                setOpacity(1.0);
            }
        }

        private void animatePill() {
            boolean systemApi = service.isUseSystemApi();
            double targetX = systemApi ? OPT_W : 0;

            TranslateTransition tt = new TranslateTransition(
                    Duration.millis(280), pill);
            tt.setToX(targetX);
            tt.setInterpolator(javafx.animation.Interpolator.SPLINE(0.4, 0, 0.2, 1));
            tt.play();

            updateOptColors();
        }

        private void movePill(boolean animated) {
            boolean systemApi = service.isUseSystemApi();
            double targetX = systemApi ? OPT_W : 0;

            if (animated) {
                TranslateTransition tt = new TranslateTransition(
                        Duration.millis(280), pill);
                tt.setToX(targetX);
                tt.setInterpolator(javafx.animation.Interpolator.SPLINE(0.4, 0, 0.2, 1));
                tt.play();
            } else {
                pill.setTranslateX(targetX);
            }

            updateOptColors();
        }

        private void updateOptColors() {
            boolean systemApi = service.isUseSystemApi();
            boolean mouseActive = !systemApi;

            String active = "-fx-font-size: 13px; -fx-font-weight: 500; -fx-text-fill: " + TEXT + ";";
            String inactive = "-fx-font-size: 13px; -fx-font-weight: 400; -fx-text-fill: " + TEXT_SEC + ";";

            optMouse.setStyle(mouseActive ? active : inactive);
            optApi.setStyle(systemApi ? active : inactive);
        }
    }

    // ── Board row helper ─────────────────────────────────────────────────

    private static class BoardRow extends HBox {
        private final Label valueNode;

        BoardRow(String key) {
            setAlignment(Pos.CENTER_LEFT);
            setPadding(new Insets(6, 0, 6, 0));
            setSpacing(0);

            Label keyLabel = new Label(key);
            keyLabel.getStyleClass().add("sk-t3");
            keyLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
            keyLabel.setMinWidth(90);

            Label sep = new Label(" │ ");
            sep.setStyle("-fx-text-fill: " + BORDER + "; -fx-font-size: 11px;");

            this.valueNode = new Label("—");
            getChildren().addAll(keyLabel, sep, this.valueNode);
        }

        void setValueNode(Label node) {
            getChildren().set(2, node);
        }
    }
}
