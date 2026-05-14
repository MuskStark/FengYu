package fan.summer.api.loading;

import fan.summer.api.config.UIConfig;
import fan.summer.i18n.I18nManager;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import javafx.geometry.Rectangle2D;

/**
 * JavaFX splash screen displayed during application initialization.
 */
public class SplashScreen {

    private Stage stage;
    private final VBox container;
    private final Label statusLabel;

    public SplashScreen() {
        // Create container
        container = new VBox();
        container.setAlignment(Pos.CENTER);
        container.setSpacing(20);
        container.setPadding(new Insets(40));
        container.setStyle("-fx-background-color: #13151a;");

        // Title
        Label title = new Label(I18nManager.get("app.name"));
        title.setFont(Font.font("SansSerif", FontWeight.BOLD, 28));
        title.setStyle("-fx-text-fill: #5b8cf7;");

        // Status
        statusLabel = new Label("Loading...");
        statusLabel.setFont(Font.font("SansSerif", FontWeight.NORMAL, 14));
        statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.48);");

        container.getChildren().addAll(title, statusLabel);

        // Create stage
        stage = new Stage();
        stage.setTitle("SwissKit");
        stage.initStyle(StageStyle.UNDECORATED);

        // Center on screen
        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        stage.setX((screenBounds.getWidth() - 320) / 2);
        stage.setY((screenBounds.getHeight() - 180) / 2);

        Scene scene = new Scene(container, 320, 180);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
    }

    public void show() {
        stage.show();

        // Fade in animation
        FadeTransition fadeIn = new FadeTransition(Duration.millis(300), container);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        fadeIn.play();
    }

    public void hide() {
        // Fade out then close
        FadeTransition fadeOut = new FadeTransition(Duration.millis(200), container);
        fadeOut.setFromValue(1);
        fadeOut.setToValue(0);
        fadeOut.setOnFinished(e -> stage.hide());
        fadeOut.play();
    }

    public void setStatus(String status) {
        statusLabel.setText(status);
    }

    public Parent getRoot() {
        return container;
    }
}