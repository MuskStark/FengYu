package fan.summer.api.component;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Glassmorphism-styled notification dialog that replaces the default JavaFX Alert.
 * <p>
 * All methods accept either a {@link Window} or a {@link Node} (resolved to its window).
 * Passing {@code null} is safe — the notification is positioned at screen center.
 *
 * <pre>
 * // Toast — auto-dismiss after 2.5 s
 * GlassNotification.toast(view, Type.SUCCESS, "Saved");
 *
 * // Notify — modal with OK
 * GlassNotification.notify(view, Type.WARNING, "Check your input");
 *
 * // Confirm — modal with OK / Cancel
 * if (GlassNotification.confirm(view, "Delete?", "This cannot be undone.")) { ... }
 * </pre>
 */
public final class GlassNotification {

    /** Notification visual style. */
    public enum Type {
        /** Informational notification with blue accent. */
        INFO("ℹ", "glass-notif-info"),
        /** Success notification with green accent. */
        SUCCESS("✓", "glass-notif-success"),
        /** Warning notification with amber accent. */
        WARNING("⚠", "glass-notif-warning"),
        /** Error notification with red accent. */
        ERROR("✕", "glass-notif-error");

        final String icon;
        final String styleClass;

        Type(String icon, String styleClass) {
            this.icon = icon;
            this.styleClass = styleClass;
        }
    }

    private GlassNotification() {}

    // ── Resolve owner from Node ──────────────────────────────────

    /**
     * Resolves the {@link Window} containing the given JavaFX node.
     *
     * @param node a JavaFX node, or {@code null}
     * @return the node's window, or {@code null} if the node is null or not in a scene
     */
    private static Window windowOf(Node node) {
        return node != null && node.getScene() != null ? node.getScene().getWindow() : null;
    }

    // ── Toast (auto-dismiss) ─────────────────────────────────────

    /**
     * Shows a non-modal, auto-dismissing toast notification (fades out after ~2.5 s).
     *
     * @param owner   the owner window; may be {@code null}
     * @param type    the visual style
     * @param message the message to display
     */
    public static void toast(Window owner, Type type, String message) {
        Platform.runLater(() -> showOverlay(owner, type, message, false, null));
    }

    /**
     * Shows a toast notification positioned over the given node's window.
     *
     * @param context a JavaFX node used to locate the owner window; may be {@code null}
     * @param type    the visual style
     * @param message the message to display
     */
    public static void toast(Node context, Type type, String message) {
        toast(windowOf(context), type, message);
    }

    // ── Notify (modal with OK) ───────────────────────────────────

    /**
     * Shows a modal notification with an OK button.
     *
     * @param owner   the owner window; may be {@code null}
     * @param type    the visual style
     * @param title   the notification title
     * @param message the body message
     */
    public static void notify(Window owner, Type type, String title, String message) {
        Platform.runLater(() -> showOverlay(owner, type, title + "\n" + message, true, null));
    }

    /**
     * Shows a modal notification positioned over the given node's window.
     *
     * @param context a JavaFX node used to locate the owner window
     * @param type    the visual style
     * @param title   the notification title
     * @param message the body message
     */
    public static void notify(Node context, Type type, String title, String message) {
        notify(windowOf(context), type, title, message);
    }

    /**
     * Shows a modal notification with an OK button (title-only variant).
     *
     * @param owner   the owner window; may be {@code null}
     * @param type    the visual style
     * @param message the message to display
     */
    public static void notify(Window owner, Type type, String message) {
        Platform.runLater(() -> showOverlay(owner, type, message, true, null));
    }

    /**
     * Shows a modal notification positioned over the given node's window (title-only variant).
     *
     * @param context a JavaFX node used to locate the owner window
     * @param type    the visual style
     * @param message the message to display
     */
    public static void notify(Node context, Type type, String message) {
        notify(windowOf(context), type, message);
    }

    // ── Confirm (modal with OK / Cancel) ─────────────────────────

    /**
     * Shows a modal confirmation dialog with OK and Cancel buttons.
     *
     * <p>Safe to call from any thread — blocks the calling thread until the
     * user responds (up to 60 seconds). Returns {@code false} on timeout
     * or interruption.</p>
     *
     * @param owner   the owner window; may be {@code null}
     * @param title   the confirmation title
     * @param message the body message
     * @return {@code true} if the user clicked OK, {@code false} otherwise
     */
    public static boolean confirm(Window owner, String title, String message) {
        if (Platform.isFxApplicationThread()) {
            return showOverlay(owner, Type.WARNING, title + "\n" + message, true, ButtonType.CANCEL);
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                boolean result = showOverlay(owner, Type.WARNING, title + "\n" + message, true, ButtonType.CANCEL);
                future.complete(result);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return false;
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Shows a modal confirmation dialog positioned over the given node's window.
     *
     * @param context a JavaFX node used to locate the owner window
     * @param title   the confirmation title
     * @param message the body message
     * @return {@code true} if the user clicked OK, {@code false} otherwise
     */
    public static boolean confirm(Node context, String title, String message) {
        return confirm(windowOf(context), title, message);
    }

    // ── Internal ──────────────────────────────────────────────────

    private static boolean showOverlay(Window owner, Type type, String message,
                                       boolean modal, ButtonType cancelType) {
        Label iconLabel = new Label(type.icon);
        iconLabel.getStyleClass().addAll("glass-notif-icon", type.styleClass);

        Text msgText = new Text(message);
        msgText.getStyleClass().add("glass-notif-message");
        msgText.setWrappingWidth(360);

        VBox content = new VBox(8, iconLabel, msgText);
        content.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(content, Priority.ALWAYS);

        HBox buttonBar = new HBox(8);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        buttonBar.getStyleClass().add("glass-notif-btn-bar");

        Button okBtn = new Button("OK");
        okBtn.getStyleClass().add("glass-notif-ok");
        okBtn.setDefaultButton(true);

        if (cancelType != null) {
            Button cancelBtn = new Button("Cancel");
            cancelBtn.getStyleClass().add("glass-notif-cancel");
            cancelBtn.setCancelButton(true);
            buttonBar.getChildren().addAll(cancelBtn, okBtn);
        } else if (modal) {
            buttonBar.getChildren().add(okBtn);
        }

        VBox root = new VBox(16, content, buttonBar);
        root.getStyleClass().add("glass-notif-root");
        root.setPadding(new Insets(20, 24, 16, 24));

        if (!modal && cancelType == null) {
            FadeTransition fadeIn = new FadeTransition(Duration.millis(200), root);
            fadeIn.setFromValue(0);
            fadeIn.setToValue(1);
            FadeTransition fadeOut = new FadeTransition(Duration.millis(300), root);
            fadeOut.setFromValue(1);
            fadeOut.setToValue(0);
            PauseTransition pause = new PauseTransition(Duration.seconds(2.5));
            SequentialTransition seq = new SequentialTransition(fadeIn, pause, fadeOut);
            seq.setOnFinished(e -> ((Stage) root.getScene().getWindow()).close());
            seq.play();
        }

        Stage stage = new Stage(StageStyle.TRANSPARENT);
        if (modal || cancelType != null) {
            stage.initModality(Modality.APPLICATION_MODAL);
        }
        if (owner != null) {
            stage.initOwner(owner);
        }

        Scene scene = new Scene(root);
        scene.setFill(null);
        scene.getStylesheets().add(GlassNotification.class.getResource("/css/swisskit-common.css").toExternalForm());
        stage.setScene(scene);

        boolean[] confirmed = {false};
        okBtn.setOnAction(e -> {
            confirmed[0] = true;
            stage.close();
        });
        if (cancelType != null) {
            stage.setOnCloseRequest(e -> stage.close());
            for (Node n : buttonBar.getChildren()) {
                if (n instanceof Button b && b != okBtn) {
                    b.setOnAction(e -> stage.close());
                }
            }
        }

        if (owner != null) {
            double cx = owner.getX() + owner.getWidth() / 2 - 210;
            double cy = owner.getY() + 60;
            stage.setX(cx);
            stage.setY(cy);
        }

        if (modal || cancelType != null) {
            stage.showAndWait();
        } else {
            stage.show();
        }

        return confirmed[0];
    }
}
