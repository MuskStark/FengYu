package fan.summer.buildintool.ai;

import fan.summer.api.IconStyle;
import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.ToolCategory;
import fan.summer.api.ToolType;
import fan.summer.api.ai.AiChatMessage;
import fan.summer.api.ai.AiService;
import fan.summer.api.ai.AiServiceProvider;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;
import fan.summer.ui.setting.SwissKitJSettingUi;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class AiChatPlugin implements SwissKitJPlugin {

    private static final PluginLogger log = LoggerFactory.getLogger(AiChatPlugin.class);

    @Override public String getId()          { return "builtin.ai-chat"; }
    @Override public String getName()        { return "AI Assistant"; }
    @Override public String getDescription() { return "Local AI chat powered by GGUF models"; }
    @Override public ToolCategory getCategory()    { return ToolCategory.OTHER; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getMdiIcon()    { return "robot-outline"; }
    @Override public IconStyle getIconStyle()   { return IconStyle.PURPLE; }
    @Override public ToolType getType()        { return ToolType.BUILTIN; }

    @Override
    public Node createView() {
        return new AiChatView();
    }

    /**
     * The main AI chat view with message list, input bar, and model loading.
     */
    private static class AiChatView extends VBox {

        private final List<AiChatMessage> history = new ArrayList<>();
        private final VBox messageList = new VBox();
        private final ScrollPane scrollPane;
        private final TextArea inputArea = new TextArea();
        private final Button sendBtn = new Button("➤");
        private final Label statusLabel = new Label();
        private final Label modelLabel = new Label("No model loaded");

        private AiService aiService;
        private boolean generating = false;
        private TextFlow currentResponseFlow;
        private StringBuilder currentResponseText;

        AiChatView() {
            getStyleClass().add("ai-chat-root");
            setSpacing(0);

            // Message list
            messageList.getStyleClass().add("ai-message-list");
            scrollPane = new ScrollPane(messageList);
            scrollPane.setFitToWidth(true);
            scrollPane.setFitToHeight(true);
            scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
            VBox.setVgrow(scrollPane, Priority.ALWAYS);

            // Model hint
            modelLabel.getStyleClass().add("ai-model-hint");

            // Toolbar
            HBox toolbar = new HBox(8, modelLabel);
            toolbar.getStyleClass().add("ai-chat-toolbar");
            toolbar.setPadding(new Insets(10, 20, 0, 20));

            // Input area
            inputArea.getStyleClass().add("ai-chat-textarea");
            inputArea.setPromptText("Type a message...");
            inputArea.setPrefRowCount(1);
            inputArea.setMaxHeight(120);
            HBox.setHgrow(inputArea, Priority.ALWAYS);

            sendBtn.getStyleClass().add("ai-send-btn");
            sendBtn.setDisable(true);
            sendBtn.setOnAction(e -> onSend());

            HBox inputBar = new HBox(8, inputArea, sendBtn);
            inputBar.getStyleClass().add("ai-chat-input-bar");
            inputBar.setPadding(new Insets(8, 20, 12, 20));
            HBox.setHgrow(inputBar, Priority.NEVER);
            inputBar.setMaxWidth(Double.MAX_VALUE);

            // Status
            statusLabel.getStyleClass().add("ai-status-label");
            statusLabel.setPadding(new Insets(0, 20, 8, 20));

            // Enter to send (Shift+Enter for newline)
            inputArea.setOnKeyPressed(e -> {
                if (e.getCode() == javafx.scene.input.KeyCode.ENTER && !e.isShiftDown()) {
                    e.consume();
                    onSend();
                }
            });

            getChildren().addAll(toolbar, scrollPane, statusLabel, inputBar);

            // Welcome message
            addSystemMessage("Welcome to AI Assistant. Configure a model in Settings → AI Model to start chatting.");

            // Check for existing service
            refreshServiceState();
        }

        private void refreshServiceState() {
            Optional<AiService> opt = AiServiceProvider.getService();
            if (opt.isPresent()) {
                aiService = opt.get();
                if (aiService.isReady()) {
                    modelLabel.setText(aiService.getModelName().orElse("Model loaded"));
                    sendBtn.setDisable(false);
                }
            }
        }

        private void onSend() {
            String text = inputArea.getText().trim();
            if (text.isEmpty() || generating) return;
            if (aiService == null || !aiService.isReady()) {
                addSystemMessage("No model loaded. Go to Settings → AI Model to configure one.");
                return;
            }

            inputArea.clear();
            history.add(AiChatMessage.user(text));
            addUserMessage(text);

            // Prepare response bubble
            currentResponseText = new StringBuilder();
            currentResponseFlow = addAssistantBubble();

            generating = true;
            sendBtn.setDisable(true);
            statusLabel.setText("Generating...");

            float temperature = SwissKitJSettingUi.getAiTemperature();
            float topP = SwissKitJSettingUi.getAiTopP();
            int maxTokens = SwissKitJSettingUi.getAiMaxTokens();

            try {
                aiService.chat(history, temperature, topP, maxTokens, new fan.summer.api.ai.AiStreamCallback() {
                    @Override
                    public void onToken(String fragment) {
                        currentResponseText.append(fragment);
                        appendToResponseFlow(fragment);
                        scrollToBottom();
                    }

                    @Override
                    public void onComplete(String fullResponse, int tokensGenerated, double tokensPerSecond) {
                        generating = false;
                        sendBtn.setDisable(false);
                        statusLabel.setText(String.format("%d tokens · %.1f tok/s", tokensGenerated, tokensPerSecond));
                        history.add(AiChatMessage.assistant(fullResponse));
                    }

                    @Override
                    public void onError(Throwable error) {
                        generating = false;
                        sendBtn.setDisable(false);
                        statusLabel.setText("Error: " + error.getMessage());
                        addSystemMessage("Error: " + error.getMessage());
                    }
                });
            } catch (Exception e) {
                generating = false;
                sendBtn.setDisable(false);
                statusLabel.setText("Error: " + e.getMessage());
            }
        }

        // ── Message rendering ──────────────────────────────────

        private void addSystemMessage(String text) {
            VBox bubble = new VBox(new Label(text));
            bubble.getStyleClass().addAll("ai-msg-bubble");
            bubble.setAlignment(Pos.CENTER);
            ((Label) bubble.getChildren().get(0)).setStyle(
                "-fx-text-fill: rgba(255,255,255,0.35); -fx-font-size: 12px; -fx-alignment: center;");

            VBox wrapper = new VBox(bubble);
            wrapper.getStyleClass().add("ai-msg-system");
            wrapper.setAlignment(Pos.CENTER);
            wrapper.setPadding(new Insets(4, 0, 4, 0));

            messageList.getChildren().add(wrapper);
            scrollToBottom();
        }

        private void addUserMessage(String text) {
            Label label = new Label("You");
            label.getStyleClass().add("ai-msg-label");

            Label bubble = new Label(text);
            bubble.getStyleClass().add("ai-msg-bubble");
            bubble.setWrapText(true);
            bubble.setMaxWidth(560);
            bubble.setStyle(
                "-fx-background-color: rgba(91,140,247,0.22);" +
                "-fx-border-color: rgba(91,140,247,0.18);" +
                "-fx-border-width: 1px; -fx-border-radius: 14px; -fx-background-radius: 14px;" +
                "-fx-text-fill: rgba(255,255,255,0.95); -fx-font-size: 13.5px; -fx-padding: 10 16 10 16;"
            );

            VBox wrapper = new VBox(4, label, bubble);
            wrapper.getStyleClass().add("ai-msg-user");
            wrapper.setAlignment(Pos.CENTER_RIGHT);
            wrapper.setPadding(new Insets(2, 0, 2, 0));

            messageList.getChildren().add(wrapper);
            scrollToBottom();
        }

        private TextFlow addAssistantBubble() {
            Label label = new Label("AI");
            label.getStyleClass().add("ai-msg-label");

            TextFlow flow = new TextFlow();
            flow.getStyleClass().add("ai-msg-bubble");
            flow.setMaxWidth(560);
            flow.setStyle(
                "-fx-background-color: rgba(255,255,255,0.055);" +
                "-fx-border-color: rgba(255,255,255,0.10);" +
                "-fx-border-width: 1px; -fx-border-radius: 14px; -fx-background-radius: 14px;" +
                "-fx-padding: 10 16 10 16;"
            );

            // Typing indicator
            Text typing = new Text("●●●");
            typing.getStyleClass().add("ai-typing-indicator");
            flow.getChildren().add(typing);

            // Blink animation for typing indicator
            FadeTransition blink = new FadeTransition(Duration.millis(800), typing);
            blink.setFromValue(1.0);
            blink.setToValue(0.3);
            blink.setAutoReverse(true);
            blink.setCycleCount(Animation.INDEFINITE);
            blink.play();
            typing.setUserData(blink);

            VBox wrapper = new VBox(4, label, flow);
            wrapper.getStyleClass().add("ai-msg-assistant");
            wrapper.setAlignment(Pos.CENTER_LEFT);
            wrapper.setPadding(new Insets(2, 0, 2, 0));

            messageList.getChildren().add(wrapper);
            scrollToBottom();
            return flow;
        }

        private void appendToResponseFlow(String fragment) {
            Platform.runLater(() -> {
                // Remove typing indicator on first token
                if (currentResponseFlow.getChildren().size() == 1) {
                    Node first = currentResponseFlow.getChildren().get(0);
                    if (first.getUserData() instanceof Animation anim) {
                        anim.stop();
                    }
                    currentResponseFlow.getChildren().clear();
                }

                Text text = new Text(fragment);
                text.setStyle("-fx-fill: rgba(255,255,255,0.90); -fx-font-size: 13.5px;");
                currentResponseFlow.getChildren().add(text);
            });
        }

        private void scrollToBottom() {
            Platform.runLater(() -> {
                scrollPane.setVvalue(1.0);
            });
        }
    }
}
