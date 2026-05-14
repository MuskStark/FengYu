package fan.summer.api.component;

import javafx.animation.*;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Generic multi-step wizard container.
 *
 * Usage:
 *   wizard.addStep("Select file", node, () -> validate());
 *   wizard.addStep("Split mode",  node, () -> true);
 *   wizard.addStep("Run split",   node, () -> true);
 *   wizard.build();
 */
public class StepWizard extends BorderPane {

    public interface StepChangeListener {
        void onStepChanged(int from, int to, int total);
    }

    private record Step(String title, Node content, BooleanSupplier canProceed) {}

    // ── State ─────────────────────────────────────────────
    private final List<Step>   steps       = new ArrayList<>();
    private int                current     = 0;
    private StepChangeListener stepListener;

    // ── Fixed child nodes ─────────────────────────────────
    private HBox   stepIndicator;
    private StackPane contentPane;
    private Button prevBtn;
    private Button nextBtn;
    private Label  stepHint;

    // ── Colour constants ──────────────────────────────────
    private static final String ACCENT     = "#5b8cf7";
    private static final String DONE_COLOR = "#4cd97b";
    private static final String IDLE_COLOR = "rgba(255,255,255,0.15)";

    public StepWizard() {
        setStyle("-fx-background-color: transparent;");
    }

    public void addStep(String title, Node content, BooleanSupplier canProceed) {
        steps.add(new Step(title, content, canProceed));
    }

    public void setOnStepChanged(StepChangeListener l) { this.stepListener = l; }

    /** Called once after all steps are added to build UI */
    public void build() {
        stepIndicator = buildStepIndicator();
        contentPane   = new StackPane();
        contentPane.setStyle("-fx-background-color: transparent;");

        // Preload all content, opacity 0, only show current step
        for (Step s : steps) {
            s.content().setOpacity(0);
            s.content().setVisible(false);
            contentPane.getChildren().add(s.content());
        }

        HBox footer = buildFooter();

        VBox top = new VBox(stepIndicator);
        top.setPadding(new Insets(0, 0, 20, 0));

        setTop(top);
        setCenter(contentPane);
        setBottom(footer);
        BorderPane.setMargin(footer, new Insets(16, 0, 0, 0));

        // Show the first step
        showStep(0, -1);
    }

    // ── Step indicator ────────────────────────────────────────

    private HBox buildStepIndicator() {
        HBox row = new HBox();
        row.setAlignment(Pos.CENTER);
        row.setPadding(new Insets(0, 0, 4, 0));

        for (int i = 0; i < steps.size(); i++) {
            // Circular step dot
            StackPane dot = makeDot(i);
            dot.setUserData(i); // Store index for refresh use
            row.getChildren().add(dot);

            // Connector line (not added after last)
            if (i < steps.size() - 1) {
                Region line = new Region();
                line.setPrefHeight(2);
                line.setMinWidth(40);
                HBox.setHgrow(line, Priority.ALWAYS);
                line.setStyle("-fx-background-color: " + IDLE_COLOR + "; -fx-background-radius: 1;");
                line.setUserData("line_" + i);
                row.getChildren().add(line);
            }
        }
        return row;
    }

    private StackPane makeDot(int idx) {
        Circle circle = new Circle(14);
        circle.setStrokeWidth(1.5);

        Label num = new Label(String.valueOf(idx + 1));
        num.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");

        Label title = new Label(steps.get(idx).title());
        title.setStyle("-fx-font-size: 10px; -fx-text-fill: rgba(255,255,255,0.45);");

        VBox labelBox = new VBox(2, num, title);
        labelBox.setAlignment(Pos.CENTER);

        StackPane dot = new StackPane(circle, labelBox);
        dot.setPrefSize(28, 28);
        StackPane.setAlignment(title, Pos.BOTTOM_CENTER);
        StackPane.setMargin(title, new Insets(42, 0, 0, 0));
        return dot;
    }

    /** Refresh all step dots and connector line styles */
    private void refreshIndicator() {
        int childIdx = 0;
        for (int i = 0; i < steps.size(); i++) {
            if (childIdx >= stepIndicator.getChildren().size()) break;
            StackPane dot = (StackPane) stepIndicator.getChildren().get(childIdx);
            Circle  circle = (Circle)  dot.getChildren().get(0);
            StackPane labelBox = (StackPane) dot.getChildren().get(1);
            Label   num    = (Label)   ((VBox) dot.getChildren().get(1)).getChildren().get(0);
            Label   title  = (Label)   ((VBox) dot.getChildren().get(1)).getChildren().get(1);
            childIdx++;

            if (i < current) {
                // Done
                circle.setFill(Color.web(DONE_COLOR, 0.9));
                circle.setStroke(Color.web(DONE_COLOR));
                num.setText("✓");
                num.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #0d0e11;");
                title.setStyle("-fx-font-size: 10px; -fx-text-fill: " + DONE_COLOR + ";");
            } else if (i == current) {
                // Current step
                circle.setFill(Color.web(ACCENT, 0.9));
                circle.setStroke(Color.web(ACCENT));
                num.setText(String.valueOf(i + 1));
                num.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: white;");
                title.setStyle("-fx-font-size: 10px; -fx-text-fill: " + ACCENT + "; -fx-font-weight: 500;");

                // Pulse animation
                ScaleTransition pulse = new ScaleTransition(Duration.millis(600), dot);
                pulse.setFromX(1.0); pulse.setFromY(1.0);
                pulse.setToX(1.12); pulse.setToY(1.12);
                pulse.setAutoReverse(true); pulse.setCycleCount(2);
                pulse.play();
            } else {
                // Not yet reached
                circle.setFill(Color.web("rgba(255,255,255,0.06)"));
                circle.setStroke(Color.web(IDLE_COLOR));
                num.setText(String.valueOf(i + 1));
                num.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: rgba(255,255,255,0.35);");
                title.setStyle("-fx-font-size: 10px; -fx-text-fill: rgba(255,255,255,0.35);");
            }

            // Connector line
            if (i < steps.size() - 1 && childIdx < stepIndicator.getChildren().size()) {
                Region line = (Region) stepIndicator.getChildren().get(childIdx);
                line.setStyle("-fx-background-color: "
                    + (i < current ? DONE_COLOR : IDLE_COLOR)
                    + "; -fx-background-radius: 1;");
                childIdx++;
            }
        }
    }

    // ── Footer buttons ──────────────────────────────────────────

    private HBox buildFooter() {
        prevBtn  = footerBtn("← ← Back", false);
        nextBtn  = footerBtn("Next →", true);
        stepHint = new Label();
        stepHint.setStyle("-fx-text-fill: rgba(255,255,255,0.28); -fx-font-size: 11px;");

        prevBtn.setOnAction(e -> goTo(current - 1));
        nextBtn.setOnAction(e -> {
            Step s = steps.get(current);
            if (!s.canProceed().getAsBoolean()) {
                shakeButton(nextBtn);
                return;
            }
            if (current < steps.size() - 1) goTo(current + 1);
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox footer = new HBox(12, prevBtn, spacer, stepHint, nextBtn);
        footer.setAlignment(Pos.CENTER_LEFT);
        return footer;
    }

    private Button footerBtn(String text, boolean primary) {
        Button btn = new Button(text);
        if (primary) {
            btn.setStyle(
                "-fx-background-color: #5b8cf7; -fx-text-fill: white;" +
                "-fx-font-size: 13px; -fx-font-weight: 500;" +
                "-fx-background-radius: 8; -fx-border-width: 0;" +
                "-fx-padding: 9 20 9 20; -fx-cursor: hand;"
            );
            btn.setOnMouseEntered(e -> btn.setStyle(btn.getStyle()
                .replace("#5b8cf7", "#4a7bf5")));
            btn.setOnMouseExited(e -> btn.setStyle(btn.getStyle()
                .replace("#4a7bf5", "#5b8cf7")));
        } else {
            btn.setStyle(
                "-fx-background-color: rgba(255,255,255,0.07);" +
                "-fx-border-color: rgba(255,255,255,0.12); -fx-border-width: 1;" +
                "-fx-text-fill: rgba(255,255,255,0.7); -fx-font-size: 13px;" +
                "-fx-background-radius: 8; -fx-border-radius: 8;" +
                "-fx-padding: 9 20 9 20; -fx-cursor: hand;"
            );
        }
        return btn;
    }

    // ── Step transition ──────────────────────────────────────────

    public void goTo(int idx) {
        if (idx < 0 || idx >= steps.size()) return;
        int from = current;
        current = idx;
        showStep(idx, from);
        if (stepListener != null) stepListener.onStepChanged(from, idx, steps.size());
    }

    private void showStep(int idx, int from) {
        refreshIndicator();
        updateFooterState();

        Node incoming = steps.get(idx).content();
        incoming.setVisible(true);

        if (from < 0) {
            // Initial display, no animation needed
            incoming.setOpacity(1);
            return;
        }

        // Determine slide direction
        double dir = idx > from ? 1 : -1;

        // Hide old step
        Node outgoing = steps.get(from).content();
        TranslateTransition outTt = new TranslateTransition(Duration.millis(220), outgoing);
        outTt.setToX(-dir * 40);
        FadeTransition outFt = new FadeTransition(Duration.millis(220), outgoing);
        outFt.setToValue(0);
        ParallelTransition out = new ParallelTransition(outTt, outFt);
        out.setOnFinished(e -> {
            outgoing.setVisible(false);
            outgoing.setTranslateX(0);
        });
        out.play();

        // Show new step
        incoming.setTranslateX(dir * 40);
        incoming.setOpacity(0);
        TranslateTransition inTt = new TranslateTransition(Duration.millis(260), incoming);
        inTt.setToX(0);
        inTt.setInterpolator(Interpolator.SPLINE(0.4, 0.0, 0.2, 1.0));
        FadeTransition inFt = new FadeTransition(Duration.millis(260), incoming);
        inFt.setToValue(1);
        new ParallelTransition(inTt, inFt).play();
    }

    private void updateFooterState() {
        prevBtn.setDisable(current == 0);
        prevBtn.setOpacity(current == 0 ? 0.4 : 1.0);

        boolean isLast = current == steps.size() - 1;
        nextBtn.setText(isLast ? "✓ Complete" : "Next →");
        stepHint.setText("Step " + (current + 1) + "  of " + steps.size() + "  steps");
    }

    /** Shake feedback when button validation fails */
    private void shakeButton(Node node) {
        TranslateTransition shake = new TranslateTransition(Duration.millis(60), node);
        shake.setFromX(0); shake.setByX(8);
        shake.setAutoReverse(true); shake.setCycleCount(4);
        shake.play();
    }

    // ── Public queries ──────────────────────────────────────────

    public int  getCurrentStep()  { return current; }
    public int  getTotalSteps()   { return steps.size(); }
    public boolean isLastStep()   { return current == steps.size() - 1; }
}
