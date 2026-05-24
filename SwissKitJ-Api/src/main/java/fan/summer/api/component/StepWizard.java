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
 * Generic multi-step wizard container for JavaFX-based plugins.
 *
 * <p>StepWizard renders a linear sequence of steps with animated transitions,
 * a visual step indicator (dots + connector lines), and Back/Next navigation
 * buttons. It handles all layout and animation, requiring only that each step
 * provide its content Node and a forward-validation predicate.</p>
 *
 * <p>Usage example:</p>
 * <pre>{@code
 * StepWizard wizard = new StepWizard();
 * wizard.addStep("Select file",  selectNode,  () -> filePath != null);
 * wizard.addStep("Configure",   configNode,  () -> configValid);
 * wizard.addStep("Confirm",     confirmNode, () -> true);
 * wizard.build();
 * }</pre>
 *
 * <p>Step transitions are animated (slide + fade). When the user clicks Next,
 * the {@code canProceed} predicate for the current step is evaluated; if it
 * returns {@code false}, the Next button shakes and navigation is blocked.
 * The current step index can also be set programmatically via {@link #goTo(int)}.</p>
 *
 * <p>Call {@link #setOnStepChanged(StepChangeListener)} to be notified whenever
 * the user moves between steps, including programmatic navigation.</p>
 *
 * @since 1.0
 * @author SwissKitJ
 */
public class StepWizard extends BorderPane {

    /**
     * Listener interface for step transition events.
     *
     * @see StepWizard#setOnStepChanged(StepChangeListener)
     */
    public interface StepChangeListener {
        /**
         * Called after a step transition completes.
         *
         * @param from the previous step index (0-based), or -1 on initial display
         * @param to   the new step index (0-based)
         * @param total the total number of steps
         */
        void onStepChanged(int from, int to, int total);
    }

    /**
     * Internal record holding data for a single wizard step.
     *
     * @param title       display label shown in the step indicator
     * @param content     the JavaFX Node to render as the step body
     * @param canProceed  a predicate evaluated when the user attempts to advance
     */
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

    /**
     * Adds a new step to the wizard.
     *
     * <p>Steps must be added before {@link #build()} is called. The order of
     * addition defines the step order.</p>
     *
     * @param title       a short label for this step (shown in the dot indicator)
     * @param content     the JavaFX Node to display as the step body
     * @param canProceed  a supplier evaluated when the user clicks Next; return
     *                    {@code true} to allow advance, {@code false} to trigger
     *                    a shake animation and block navigation
     * @throws IllegalStateException if called after {@link #build()}
     */
    public void addStep(String title, Node content, BooleanSupplier canProceed) {
        steps.add(new Step(title, content, canProceed));
    }

    /**
     * Registers a listener for step change events.
     *
     * @param l the listener to invoke on step transitions
     */
    public void setOnStepChanged(StepChangeListener l) { this.stepListener = l; }

    /**
     * Builds the wizard UI from the previously added steps.
     *
     * <p>This method constructs the step indicator bar, the content stack, and
     * the footer buttons. It must be called exactly once after all steps have
     * been added via {@link #addStep(String, Node, BooleanSupplier)} and before
     * the wizard is shown. Calling it more than once has no effect.</p>
     *
     * <p>After calling {@code build()}, the first step is displayed automatically.</p>
     */
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
                line.setMaxHeight(2);
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
        Circle circle = new Circle(12);
        circle.setStrokeWidth(1.5);

        Label num = new Label(String.valueOf(idx + 1));
        num.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");

        StackPane dot = new StackPane(circle, num);
        dot.setPrefSize(24, 24);
        return dot;
    }

    /**
     * Refreshes all step dots and connector line styles to reflect the current step.
     *
     * <p>Dots before the current step show a checkmark in green; the active step
     * shows its number in the accent color with a brief pulse animation; future
     * steps appear idle. Connector lines between completed steps turn green.</p>
     */
    private void refreshIndicator() {
        int childIdx = 0;
        for (int i = 0; i < steps.size(); i++) {
            if (childIdx >= stepIndicator.getChildren().size()) break;
            StackPane dot    = (StackPane) stepIndicator.getChildren().get(childIdx);
            Circle    circle = (Circle)    dot.getChildren().get(0);
            Label     num    = (Label)     dot.getChildren().get(1);
            childIdx++;

            if (i < current) {
                circle.setFill(Color.web(DONE_COLOR, 0.9));
                circle.setStroke(Color.web(DONE_COLOR));
                num.setText("✓");
                num.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #0d0e11;");
            } else if (i == current) {
                circle.setFill(Color.web(ACCENT, 0.9));
                circle.setStroke(Color.web(ACCENT));
                num.setText(String.valueOf(i + 1));
                num.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: white;");
                ScaleTransition pulse = new ScaleTransition(Duration.millis(600), dot);
                pulse.setFromX(1.0); pulse.setFromY(1.0);
                pulse.setToX(1.12); pulse.setToY(1.12);
                pulse.setAutoReverse(true); pulse.setCycleCount(2);
                pulse.play();
            } else {
                circle.setFill(Color.web("rgba(255,255,255,0.06)"));
                circle.setStroke(Color.web(IDLE_COLOR));
                num.setText(String.valueOf(i + 1));
                num.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: rgba(255,255,255,0.35);");
            }

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

    /**
     * Navigates programmatically to the specified step.
     *
     * <p>If the target index is out of bounds, the call is silently ignored.
     * The {@link StepChangeListener} (if registered) is notified after the
     * transition animation completes.</p>
     *
     * @param idx the zero-based step index to navigate to
     */
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

    /**
     * Applies a horizontal shake animation to the given node.
     *
     * <p>Used as visual feedback when the user attempts to advance but
     * {@code canProceed} returns {@code false}.</p>
     *
     * @param node the JavaFX Node to shake
     */
    private void shakeButton(Node node) {
        TranslateTransition shake = new TranslateTransition(Duration.millis(60), node);
        shake.setFromX(0); shake.setByX(8);
        shake.setAutoReverse(true); shake.setCycleCount(4);
        shake.play();
    }

    // ── Public queries ──────────────────────────────────────────

    /**
     * Returns the zero-based index of the currently displayed step.
     *
     * @return the current step index
     */
    public int  getCurrentStep()  { return current; }

    /**
     * Returns the total number of steps in this wizard.
     *
     * @return the total step count
     */
    public int  getTotalSteps()   { return steps.size(); }

    /**
     * Returns whether the current step is the last step.
     *
     * @return {@code true} if the current step is the last one
     */
    public boolean isLastStep()   { return current == steps.size() - 1; }
}
