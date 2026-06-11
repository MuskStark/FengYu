package fan.summer.buildintool.email;

import javafx.animation.FillTransition;
import javafx.animation.TranslateTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * A small animated on/off toggle switch (iOS-style). Exposes a {@link #selectedProperty()}
 * so callers can observe / bind the on-off state just like a {@code CheckBox}. Clicking the
 * switch (or pressing it via keyboard focus) toggles the state with a sliding thumb animation.
 */
public class ToggleSwitch extends StackPane {

    private static final double WIDTH = 44;
    private static final double HEIGHT = 24;
    private static final double THUMB_RADIUS = 9;
    /** Horizontal travel of the thumb from centre, in either direction. */
    private static final double OFFSET = WIDTH / 2 - THUMB_RADIUS - 3;

    private static final Color OFF_COLOR = Color.rgb(255, 255, 255, 0.18);
    private static final Color ON_COLOR = Color.web("#5b8cf7");

    private final BooleanProperty selected = new SimpleBooleanProperty(false);
    private final Rectangle track;
    private final Circle thumb;

    public ToggleSwitch() {
        track = new Rectangle(WIDTH, HEIGHT);
        track.setArcWidth(HEIGHT);
        track.setArcHeight(HEIGHT);
        track.setFill(OFF_COLOR);

        thumb = new Circle(THUMB_RADIUS);
        thumb.setFill(Color.WHITE);
        thumb.setTranslateX(-OFFSET);

        getChildren().addAll(track, thumb);
        setMinSize(WIDTH, HEIGHT);
        setPrefSize(WIDTH, HEIGHT);
        setMaxSize(WIDTH, HEIGHT);
        setStyle("-fx-cursor: hand;");

        setOnMouseClicked(e -> setSelected(!isSelected()));
        selected.addListener((obs, was, now) -> animate(now));
    }

    private void animate(boolean on) {
        TranslateTransition slide = new TranslateTransition(Duration.millis(150), thumb);
        slide.setToX(on ? OFFSET : -OFFSET);

        FillTransition fade = new FillTransition(Duration.millis(150), track,
                (Color) track.getFill(), on ? ON_COLOR : OFF_COLOR);

        slide.play();
        fade.play();
    }

    public boolean isSelected() {
        return selected.get();
    }

    public void setSelected(boolean value) {
        selected.set(value);
    }

    public BooleanProperty selectedProperty() {
        return selected;
    }
}
