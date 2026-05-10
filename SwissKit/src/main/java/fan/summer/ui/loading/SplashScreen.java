package fan.summer.ui.loading;

import fan.summer.api.config.UIConfig;
import fan.summer.i18n.I18nManager;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

public class SplashScreen {
    private final Parent root;
    private final Label statusLabel;

    public SplashScreen() {
        VBox container = new VBox();
        container.setAlignment(Pos.CENTER);
        container.setSpacing(20);
        container.setPadding(new Insets(40));
        container.setStyle("-fx-background-color: white;");

        Label title = new Label(I18nManager.get("app.name"));
        title.setFont(Font.font("SansSerif", FontWeight.BOLD, 24));
        title.setStyle("-fx-text-fill: " + UIConfig.PRIMARY_COLOR + ";");

        statusLabel = new Label("Loading...");
        statusLabel.setFont(Font.font("SansSerif", FontWeight.NORMAL, 14));
        statusLabel.setStyle("-fx-text-fill: " + UIConfig.SECONDARY_TEXT + ";");

        container.getChildren().addAll(title, statusLabel);
        this.root = container;

        FadeTransition fadeIn = new FadeTransition(Duration.millis(300), root);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        fadeIn.play();
    }

    public Parent getRoot() {
        return root;
    }

    public void setStatus(String status) {
        statusLabel.setText(status);
    }

    public void close() {
        FadeTransition fadeOut = new FadeTransition(Duration.millis(200), root);
        fadeOut.setFromValue(1);
        fadeOut.setToValue(0);
        fadeOut.setOnFinished(e -> {
            javafx.stage.Stage stage = (javafx.stage.Stage) root.getScene().getWindow();
            if (stage != null) {
                stage.close();
            }
        });
        fadeOut.play();
    }
}