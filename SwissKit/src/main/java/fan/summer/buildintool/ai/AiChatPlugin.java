package fan.summer.buildintool.ai;

import fan.summer.api.IconStyle;
import fan.summer.api.SwissKitJPlugin;
import fan.summer.api.ToolCategory;
import fan.summer.api.ToolType;
import fan.summer.api.ai.*;
import fan.summer.api.i18n.I18n;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;
import fan.summer.ui.setting.SwissKitJSettingUi;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class AiChatPlugin implements SwissKitJPlugin {

    private static final PluginLogger log = LoggerFactory.getLogger(AiChatPlugin.class);

    @Override public String getId()          { return "builtin.ai-chat"; }
    @Override public String getName()        { return I18n.get("builtin.ai-chat.name"); }
    @Override public String getDescription() { return I18n.get("builtin.ai-chat.desc"); }
    @Override public ToolCategory getCategory()    { return ToolCategory.OTHER; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getMdiIcon()    { return "robot-outline"; }
    @Override public IconStyle getIconStyle()   { return IconStyle.PURPLE; }
    @Override public ToolType getType()        { return ToolType.BUILTIN; }

    @Override
    public Node createView() {
        return new AiChatView();
    }

    private record Attachment(String name, String content, long sizeBytes) {}

    private static class AiChatView extends VBox {

        private static final long MAX_ATTACHMENT_BYTES = 200 * 1024; // 200 KB per file

        private final List<AiChatMessage> history = new ArrayList<>();
        private final List<Attachment> pendingAttachments = new ArrayList<>();
        private final VBox messageList = new VBox();
        private final ScrollPane scrollPane;
        private final TextArea inputArea = new TextArea();
        private final Button sendBtn = new Button("➤");
        private final Button attachBtn = new Button("📎");
        private final FlowPane attachmentBar = new FlowPane();
        private final Label statusLabel = new Label();
        private final Label modelLabel = new Label(I18n.get("builtin.ai.noModelLoaded"));

        private AiService aiService;
        private boolean generating = false;
        private Label currentResponseLabel;
        private StringBuilder currentResponseText;

        AiChatView() {
            getStyleClass().add("ai-chat-root");
            setSpacing(0);

            messageList.getStyleClass().add("ai-message-list");
            scrollPane = new ScrollPane(messageList);
            scrollPane.setFitToWidth(true);
            scrollPane.setFitToHeight(false);
            scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
            VBox.setVgrow(scrollPane, Priority.ALWAYS);

            modelLabel.getStyleClass().add("ai-model-hint");

            HBox toolbar = new HBox(8, modelLabel);
            toolbar.getStyleClass().add("ai-chat-toolbar");
            toolbar.setPadding(new Insets(10, 20, 0, 20));

            attachmentBar.getStyleClass().add("ai-attachment-bar");
            attachmentBar.setHgap(6);
            attachmentBar.setVgap(6);
            attachmentBar.setPadding(new Insets(0, 20, 4, 20));
            attachmentBar.setManaged(false);
            attachmentBar.setVisible(false);

            inputArea.getStyleClass().add("ai-chat-textarea");
            inputArea.setPromptText(I18n.get("builtin.ai.typeMessage"));
            inputArea.setPrefRowCount(1);
            inputArea.setMaxHeight(120);
            HBox.setHgrow(inputArea, Priority.ALWAYS);

            attachBtn.getStyleClass().add("ai-attach-btn");
            attachBtn.setOnAction(e -> onPickAttachment());

            sendBtn.getStyleClass().add("ai-send-btn");
            sendBtn.setDisable(true);
            sendBtn.setOnAction(e -> onSend());

            HBox inputBar = new HBox(6, attachBtn, inputArea, sendBtn);
            inputBar.getStyleClass().add("ai-chat-input-bar");
            inputBar.setPadding(new Insets(8, 20, 12, 20));
            HBox.setHgrow(inputBar, Priority.NEVER);
            inputBar.setMaxWidth(Double.MAX_VALUE);

            statusLabel.getStyleClass().add("ai-status-label");
            statusLabel.setPadding(new Insets(0, 20, 8, 20));

            inputArea.setOnKeyPressed(e -> {
                if (e.getCode() == javafx.scene.input.KeyCode.ENTER && !e.isShiftDown()) {
                    e.consume();
                    onSend();
                }
            });

            getChildren().addAll(toolbar, scrollPane, statusLabel, attachmentBar, inputBar);

            addSystemMessage(I18n.get("builtin.ai.welcomeMessage"));

            refreshServiceState();
            AiServiceProvider.addOnStateChangeListener(this::refreshServiceState);
        }

        private void refreshServiceState() {
            Platform.runLater(() -> {
                Optional<AiService> opt = AiServiceProvider.getService();
                boolean ready = false;
                if (opt.isPresent()) {
                    aiService = opt.get();
                    ready = aiService.isReady();
                    modelLabel.setText(ready ? aiService.getModelName().orElse(I18n.get("builtin.ai.modelLoaded")) : I18n.get("builtin.ai.noModelLoaded"));
                } else {
                    modelLabel.setText(I18n.get("builtin.ai.noModelLoaded"));
                }
                sendBtn.setDisable(!ready);
                attachBtn.setDisable(!ready);
            });
        }

        // ── File attachment ────────────────────────────────────

        private void onPickAttachment() {
            if (generating) return;
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.get("builtin.ai.attachFiles"));
            chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Text files",
                    "*.txt", "*.md", "*.markdown", "*.log",
                    "*.json", "*.yaml", "*.yml", "*.toml", "*.xml", "*.html", "*.htm",
                    "*.css", "*.js", "*.ts", "*.tsx", "*.jsx",
                    "*.java", "*.kt", "*.kts", "*.py", "*.go", "*.rs", "*.c", "*.h",
                    "*.cpp", "*.hpp", "*.cs", "*.rb", "*.php", "*.swift", "*.sh", "*.bat",
                    "*.sql", "*.csv", "*.tsv", "*.properties", "*.gradle", "*.ini", "*.conf"),
                new FileChooser.ExtensionFilter("All files", "*.*")
            );
            List<java.io.File> files = chooser.showOpenMultipleDialog(getScene().getWindow());
            if (files == null || files.isEmpty()) return;
            for (java.io.File f : files) {
                loadAttachment(f.toPath()).ifPresent(att -> {
                    pendingAttachments.add(att);
                    log.info("Attached file: {} ({} bytes)", att.name(), att.sizeBytes());
                });
            }
            renderAttachmentBar();
        }

        private Optional<Attachment> loadAttachment(Path path) {
            try {
                long size = Files.size(path);
                if (size > MAX_ATTACHMENT_BYTES) {
                    addSystemMessage(I18n.get("builtin.ai.fileTooLarge", MAX_ATTACHMENT_BYTES / 1024, path.getFileName()));
                    return Optional.empty();
                }
                byte[] bytes = Files.readAllBytes(path);
                if (isLikelyBinary(bytes)) {
                    addSystemMessage(I18n.get("builtin.ai.cannotAttachBinary", path.getFileName()));
                    return Optional.empty();
                }
                String content = new String(bytes, StandardCharsets.UTF_8);
                return Optional.of(new Attachment(path.getFileName().toString(), content, size));
            } catch (IOException e) {
                addSystemMessage(I18n.get("builtin.ai.failedToRead", path.getFileName(), e.getMessage()));
                return Optional.empty();
            }
        }

        private boolean isLikelyBinary(byte[] bytes) {
            int sample = Math.min(bytes.length, 4096);
            int nul = 0;
            for (int i = 0; i < sample; i++) if (bytes[i] == 0) nul++;
            return nul > 0;
        }

        private void renderAttachmentBar() {
            attachmentBar.getChildren().clear();
            if (pendingAttachments.isEmpty()) {
                attachmentBar.setManaged(false);
                attachmentBar.setVisible(false);
                return;
            }
            for (Attachment att : pendingAttachments) {
                attachmentBar.getChildren().add(buildAttachmentChip(att));
            }
            attachmentBar.setManaged(true);
            attachmentBar.setVisible(true);
        }

        private Node buildAttachmentChip(Attachment att) {
            Label icon = new Label("📄");
            icon.setStyle("-fx-font-size: 11px;");
            Label name = new Label(att.name() + "  " + humanSize(att.sizeBytes()));
            name.setStyle("-fx-text-fill: rgba(255,255,255,0.80); -fx-font-size: 11px;");
            Button remove = new Button("×");
            remove.getStyleClass().add("ai-chip-remove");
            remove.setOnAction(e -> {
                pendingAttachments.remove(att);
                renderAttachmentBar();
            });
            HBox chip = new HBox(4, icon, name, remove);
            chip.getStyleClass().add("ai-attachment-chip");
            chip.setAlignment(Pos.CENTER_LEFT);
            return chip;
        }

        private static String humanSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }

        // ── Send ───────────────────────────────────────────────

        private void onSend() {
            String text = inputArea.getText().trim();
            if (text.isEmpty() && pendingAttachments.isEmpty()) return;
            if (generating) return;
            if (aiService == null || !aiService.isReady()) {
                addSystemMessage(I18n.get("builtin.ai.noModelConfigured"));
                return;
            }

            List<Attachment> snapshot = new ArrayList<>(pendingAttachments);
            String promptForModel = buildPromptWithAttachments(text, snapshot);

            inputArea.clear();
            pendingAttachments.clear();
            renderAttachmentBar();

            history.add(AiChatMessage.user(promptForModel));
            addUserMessage(text, snapshot);

            currentResponseText = new StringBuilder();
            currentResponseLabel = addAssistantBubble();

            generating = true;
            sendBtn.setDisable(true);
            attachBtn.setDisable(true);
            statusLabel.setText(I18n.get("builtin.ai.generating"));

            float temperature = SwissKitJSettingUi.getAiTemperature();
            float topP = SwissKitJSettingUi.getAiTopP();
            int maxTokens = SwissKitJSettingUi.getAiMaxTokens();

            try {
                aiService.chat(history, temperature, topP, maxTokens, new AiStreamCallback() {
                    @Override
                    public void onToken(String fragment) {
                        currentResponseText.append(fragment);
                        String display = stripSpecialTokens(currentResponseText.toString());
                        updateResponseBubble(display);
                        scrollToBottom();
                    }

                    @Override
                    public void onToolCall(AiToolCall toolCall) {
                        Platform.runLater(() -> {
                            stopTypingAnimation(currentResponseLabel);
                            if (currentResponseText.isEmpty()) {
                                messageList.getChildren().removeIf(n ->
                                    n instanceof VBox vb && vb.getChildren().contains(currentResponseLabel));
                            }
                            addToolCallCard(toolCall);
                            currentResponseText = new StringBuilder();
                            currentResponseLabel = addAssistantBubble();
                            scrollToBottom();
                        });
                    }

                    @Override
                    public void onToolResult(String toolCallId, AiToolResult result) {
                        Platform.runLater(() -> {
                            addToolResultCard(result);
                            scrollToBottom();
                        });
                    }

                    @Override
                    public void onComplete(String fullResponse, int tokensGenerated, double tokensPerSecond) {
                        generating = false;
                        sendBtn.setDisable(false);
                        attachBtn.setDisable(false);
                        statusLabel.setText(String.format("%d tokens · %.1f tok/s", tokensGenerated, tokensPerSecond));
                        if (currentResponseText != null) {
                            String display = stripSpecialTokens(currentResponseText.toString());
                            updateResponseBubble(display);
                        }
                        if (fullResponse != null && !fullResponse.isBlank()) {
                            String cleaned = stripSpecialTokens(fullResponse);
                            if (!cleaned.isBlank()) {
                                history.add(AiChatMessage.assistant(cleaned));
                            }
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        generating = false;
                        sendBtn.setDisable(false);
                        attachBtn.setDisable(false);
                        statusLabel.setText("Error: " + error.getMessage());
                        addSystemMessage("Error: " + error.getMessage());
                    }
                });
            } catch (Exception e) {
                generating = false;
                sendBtn.setDisable(false);
                attachBtn.setDisable(false);
                statusLabel.setText("Error: " + e.getMessage());
            }
        }

        private String buildPromptWithAttachments(String userText, List<Attachment> attachments) {
            if (attachments.isEmpty()) return userText;
            StringBuilder sb = new StringBuilder();
            sb.append("The user has attached the following file(s). Use their contents as context for the question.\n\n");
            for (Attachment att : attachments) {
                sb.append("===== ").append(att.name()).append(" =====\n");
                sb.append(att.content());
                if (!att.content().endsWith("\n")) sb.append('\n');
                sb.append('\n');
            }
            if (!userText.isEmpty()) {
                sb.append("User question:\n").append(userText);
            } else {
                sb.append("Please analyze the attached file(s).");
            }
            return sb.toString();
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

        private void addUserMessage(String text, List<Attachment> attachments) {
            Label label = new Label(I18n.get("builtin.ai.you"));
            label.getStyleClass().add("ai-msg-label");

            VBox wrapper = new VBox(4, label);
            wrapper.getStyleClass().add("ai-msg-user");
            wrapper.setAlignment(Pos.CENTER_RIGHT);
            wrapper.setPadding(new Insets(2, 0, 2, 0));

            if (!attachments.isEmpty()) {
                FlowPane chips = new FlowPane();
                chips.setHgap(6);
                chips.setVgap(4);
                chips.setAlignment(Pos.CENTER_RIGHT);
                chips.setMaxWidth(560);
                for (Attachment att : attachments) {
                    Label chip = new Label("📄 " + att.name() + "  " + humanSize(att.sizeBytes()));
                    chip.setStyle(
                        "-fx-background-color: rgba(91,140,247,0.15);" +
                        "-fx-border-color: rgba(91,140,247,0.25);" +
                        "-fx-border-width: 1px; -fx-border-radius: 10px; -fx-background-radius: 10px;" +
                        "-fx-text-fill: rgba(255,255,255,0.85); -fx-font-size: 11px; -fx-padding: 3 8 3 8;"
                    );
                    chips.getChildren().add(chip);
                }
                wrapper.getChildren().add(chips);
            }

            if (!text.isEmpty()) {
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
                wrapper.getChildren().add(bubble);
            }

            messageList.getChildren().add(wrapper);
            scrollToBottom();
        }

        private Label addAssistantBubble() {
            Label label = new Label("AI");
            label.getStyleClass().add("ai-msg-label");

            Label bubble = new Label("●●●");
            bubble.getStyleClass().add("ai-msg-bubble");
            bubble.setWrapText(true);
            bubble.setMaxWidth(560);
            bubble.setStyle(
                "-fx-background-color: rgba(255,255,255,0.055);" +
                "-fx-border-color: rgba(255,255,255,0.10);" +
                "-fx-border-width: 1px; -fx-border-radius: 14px; -fx-background-radius: 14px;" +
                "-fx-text-fill: rgba(255,255,255,0.40); -fx-font-size: 12px;"
            );

            FadeTransition blink = new FadeTransition(Duration.millis(800), bubble);
            blink.setFromValue(1.0);
            blink.setToValue(0.3);
            blink.setAutoReverse(true);
            blink.setCycleCount(Animation.INDEFINITE);
            blink.play();
            bubble.setUserData(blink);

            VBox wrapper = new VBox(4, label, bubble);
            wrapper.getStyleClass().add("ai-msg-assistant");
            wrapper.setAlignment(Pos.CENTER_LEFT);
            wrapper.setPadding(new Insets(2, 0, 2, 0));

            messageList.getChildren().add(wrapper);
            scrollToBottom();
            return bubble;
        }

        private void stopTypingAnimation(Label bubble) {
            if (bubble == null) return;
            if (bubble.getUserData() instanceof Animation anim) {
                anim.stop();
            }
        }

        private void addToolCallCard(AiToolCall toolCall) {
            Label icon = new Label("⚙");
            icon.setStyle("-fx-text-fill: #f59f00; -fx-font-size: 13px;");

            Label name = new Label(I18n.get("builtin.ai.calling", toolCall.name()));
            name.setStyle("-fx-text-fill: rgba(255,255,255,0.85); -fx-font-size: 12px; -fx-font-weight: bold;");

            String argsStr = toolCall.arguments().entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining(", "));

            Label args = new Label(argsStr);
            args.setWrapText(true);
            args.setMaxWidth(480);
            args.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 11px;");

            VBox content = new VBox(4, new HBox(6, icon, name), args);

            HBox card = new HBox(content);
            card.setStyle(
                "-fx-background-color: rgba(245,159,0,0.10);" +
                "-fx-border-color: rgba(245,159,0,0.20);" +
                "-fx-border-width: 1px; -fx-border-radius: 10px; -fx-background-radius: 10px;" +
                "-fx-padding: 8 14 8 14;"
            );
            card.setMaxWidth(520);

            VBox wrapper = new VBox(card);
            wrapper.setAlignment(Pos.CENTER_LEFT);
            wrapper.setPadding(new Insets(4, 0, 4, 0));

            messageList.getChildren().add(wrapper);
            scrollToBottom();
        }

        private void addToolResultCard(AiToolResult result) {
            Label icon = new Label(result.success() ? "✓" : "✗");
            icon.setStyle("-fx-text-fill: " + (result.success() ? "#51cf66" : "#ff6b6b") + "; -fx-font-size: 13px;");

            Label label = new Label(result.success() ? I18n.get("builtin.ai.toolResult") : I18n.get("builtin.ai.toolError"));
            label.setStyle("-fx-text-fill: rgba(255,255,255,0.70); -fx-font-size: 11px; -fx-font-weight: bold;");

            Label output = new Label(result.output());
            output.setWrapText(true);
            output.setMaxWidth(480);
            output.setStyle("-fx-text-fill: rgba(255,255,255,0.65); -fx-font-size: 11px;");

            VBox content = new VBox(4, new HBox(6, icon, label), output);

            HBox card = new HBox(content);
            String borderColor = result.success()
                ? "rgba(81,207,102,0.20)" : "rgba(255,107,107,0.20)";
            String bgColor = result.success()
                ? "rgba(81,207,102,0.06)" : "rgba(255,107,107,0.06)";
            card.setStyle(
                "-fx-background-color: " + bgColor + ";" +
                "-fx-border-color: " + borderColor + ";" +
                "-fx-border-width: 1px; -fx-border-radius: 10px; -fx-background-radius: 10px;" +
                "-fx-padding: 8 14 8 14;"
            );
            card.setMaxWidth(520);

            VBox wrapper = new VBox(card);
            wrapper.setAlignment(Pos.CENTER_LEFT);
            wrapper.setPadding(new Insets(2, 0, 2, 0));

            messageList.getChildren().add(wrapper);
            scrollToBottom();
        }

        private void updateResponseBubble(String displayText) {
            Platform.runLater(() -> {
                if (currentResponseLabel == null) return;
                if (displayText.isEmpty()) return;

                if (currentResponseLabel.getUserData() instanceof Animation anim) {
                    anim.stop();
                    currentResponseLabel.setUserData(null);
                    currentResponseLabel.setStyle(
                        "-fx-background-color: rgba(255,255,255,0.055);" +
                        "-fx-border-color: rgba(255,255,255,0.10);" +
                        "-fx-border-width: 1px; -fx-border-radius: 14px; -fx-background-radius: 14px;" +
                        "-fx-text-fill: rgba(255,255,255,0.90); -fx-font-size: 13.5px;"
                    );
                }

                currentResponseLabel.setText(displayText);
            });
        }

        private String stripSpecialTokens(String text) {
            String r = text;
            r = r.replaceAll("(?s)<think[^>]*>.*?</think\\s*>", "");
            r = r.replaceAll("(?s)<think[^>]*>.*", "");
            r = r.replaceAll("<[^>]+>", "");
            r = r.replaceAll("<[^>]*$", "");
            return r;
        }

        private void scrollToBottom() {
            Platform.runLater(() -> scrollPane.setVvalue(1.0));
        }
    }
}
