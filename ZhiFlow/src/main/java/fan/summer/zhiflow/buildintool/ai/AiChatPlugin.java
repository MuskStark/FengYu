package fan.summer.zhiflow.buildintool.ai;

import fan.summer.zhiflow.api.IconStyle;
import fan.summer.zhiflow.api.ZhiFlowPlugin;
import fan.summer.zhiflow.api.ToolCategory;
import fan.summer.zhiflow.api.ToolType;
import fan.summer.zhiflow.api.ai.*;
import fan.summer.zhiflow.api.i18n.I18n;
import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;
import fan.summer.zhiflow.api.theme.ThemeService;
import fan.summer.zhiflow.ai.SlashCommandHandler;
import fan.summer.zhiflow.ai.ToolExecutor;
import fan.summer.zhiflow.ai.util.MarkdownRenderer;
import fan.summer.zhiflow.ui.setting.ZhiFlowSettingUi;
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
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Built-in AI chat plugin for ZhiFlow.
 *
 * <p>Provides a conversational interface backed by a language model via
 * {@link ChatBackend}. Supports file attachments, streaming token delivery,
 * tool-call orchestration through {@link AiToolCall}, and Markdown rendering
 * in assistant responses.</p>
 *
 * <p>The plugin maintains a message history that is sent to the model on each
 * user turn, including any attached file contents as part of the prompt.</p>
 *
 * <p>Tool execution is handled transparently: when the model emits a tool-call
 * message, the result is injected back into the conversation via
 * {@link AiStreamCallback#onToolResult(String, AiToolResult)}.</p>
 *
 * @see ChatBackend
 * @see AiStreamCallback
 * @see AiToolCall
 */

public class AiChatPlugin implements ZhiFlowPlugin {

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
        log.debug("Creating AI Chat view");
        return new AiChatView();
    }

    @Override
    public void onActivate() {
        log.info("AI Chat plugin activated");
        fan.summer.zhiflow.ui.setting.ZhiFlowSettingUi.ensureLocalBackend();
    }

    @Override
    public void onDeactivate() {
        log.info("AI Chat plugin deactivated");
    }

    private record Attachment(String name, String content, long sizeBytes) {}

    private static class AiChatView extends VBox {

        private static final long MAX_ATTACHMENT_BYTES = 200 * 1024;

        private final List<AiChatMessage> history = new ArrayList<>();
        private final List<Attachment> pendingAttachments = new ArrayList<>();
        private final VBox messageList = new VBox();
        /**
         * Tracks every WebView whose content comes from {@link MarkdownRenderer}
         * so the conversation can be re-rendered losslessly when the theme
         * changes. Each entry stores the RAW markdown plus the render kind, so
         * {@link #rerenderConversation()} can rebuild the HTML in the new theme.
         */
        private final List<ReRenderable> reRenderables = new ArrayList<>();
        private final ScrollPane scrollPane;
        private final TextArea inputArea = new TextArea();
        private final Button sendBtn = new Button("➤");
        private final Button attachBtn = new Button("📎");
        private final FlowPane attachmentBar = new FlowPane();
        private final Label statusLabel = new Label();
        private final Label modelLabel = new Label(I18n.get("builtin.ai.noModelLoaded"));
        private final Label nativeUnavailableBanner = new Label();

        private ChatBackend aiService;
        private boolean generating = false;
        private WebView currentResponseView;
        private VBox currentAssistantWrapper;
        private StringBuilder currentResponseText;

        /** How a tracked WebView should be re-rendered on theme change. */
        private enum RenderKind { FULL, PLAIN, COLLAPSIBLE }

        /**
         * Mutable holder for one re-renderable WebView: the raw markdown (so
         * re-render is lossless) plus the render kind and (for collapsible)
         * the title. The markdown and kind are updated in place as the
         * assistant response streams / finalizes.
         */
        private static final class ReRenderable {
            final WebView webView;
            String rawMarkdown;
            RenderKind kind;
            final String collapsibleTitle;

            ReRenderable(WebView webView, String rawMarkdown, RenderKind kind, String collapsibleTitle) {
                this.webView = webView;
                this.rawMarkdown = rawMarkdown;
                this.kind = kind;
                this.collapsibleTitle = collapsibleTitle;
            }
        }

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

            nativeUnavailableBanner.setText(I18n.get("builtin.ai.nativeUnavailable"));
            nativeUnavailableBanner.getStyleClass().add("sk-t2");
            nativeUnavailableBanner.setStyle(
                "-fx-background-color: rgba(245,159,0,0.12);" +
                "-fx-border-color: rgba(245,159,0,0.25);" +
                "-fx-border-width: 0 0 1px 0;" +
                "-fx-font-size: 12px; -fx-padding: 6 20 6 20;" +
                "-fx-alignment: center;"
            );
            nativeUnavailableBanner.setMaxWidth(Double.MAX_VALUE);
            nativeUnavailableBanner.setManaged(false);
            nativeUnavailableBanner.setVisible(false);

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
            HBox.setHgrow(inputBar, Priority.ALWAYS);
            inputBar.setMaxWidth(Double.MAX_VALUE);

            statusLabel.getStyleClass().add("ai-status-label");
            statusLabel.setPadding(new Insets(0, 20, 8, 20));

            inputArea.setOnKeyPressed(e -> {
                if (e.getCode() == javafx.scene.input.KeyCode.ENTER && !e.isShiftDown()) {
                    e.consume();
                    onSend();
                }
            });

            getChildren().addAll(toolbar, nativeUnavailableBanner, scrollPane, statusLabel, attachmentBar, inputBar);

            addSystemMessage(I18n.get("builtin.ai.welcomeMessage"));

            refreshServiceState();
            AiServiceProvider.addOnStateChangeListener(this::refreshServiceState);

            // Live re-render on theme change: MarkdownRenderer is theme-aware,
            // so re-running it produces HTML for the new palette. Also re-applies
            // the WebView container bg so the area around each bubble matches.
            ThemeService.onChange(t -> Platform.runLater(this::rerenderConversation));
        }

        private void refreshServiceState() {
            Platform.runLater(() -> {
                Optional<ChatBackend> opt = AiServiceProvider.getService();
                boolean ready = false;
                boolean isLocal = "local".equals(AiServiceProvider.getCurrentMode());
                if (opt.isPresent()) {
                    aiService = opt.get();
                    ready = aiService.isReady();
                    modelLabel.setText(ready ? aiService.getModelName().orElse(I18n.get("builtin.ai.modelLoaded")) : I18n.get("builtin.ai.noModelLoaded"));
                } else {
                    modelLabel.setText(I18n.get("builtin.ai.noModelLoaded"));
                }
                sendBtn.setDisable(!ready);
                attachBtn.setDisable(!ready);

                // Show native-unavailable banner when using local backend without native acceleration
                boolean showDegraded = isLocal && opt.isPresent() && !AiServiceProvider.isNativeAvailable();
                nativeUnavailableBanner.setVisible(showDegraded);
                nativeUnavailableBanner.setManaged(showDegraded);
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
            name.getStyleClass().add("sk-t1");
            name.setStyle("-fx-font-size: 11px;");
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

            // Slash command interception — bypass model for direct tool execution
            if (text.startsWith("/")) {
                inputArea.clear();
                pendingAttachments.clear();
                renderAttachmentBar();
                handleSlashCommand(text);
                return;
            }

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
            currentResponseView = addAssistantBubble();

            generating = true;
            sendBtn.setDisable(true);
            attachBtn.setDisable(true);
            statusLabel.setText(I18n.get("builtin.ai.generating"));

            float temperature = ZhiFlowSettingUi.getAiTemperature();
            float topP = ZhiFlowSettingUi.getAiTopP();
            int maxTokens = ZhiFlowSettingUi.getAiMaxTokens();

            try {
                aiService.chat(history, temperature, topP, maxTokens, new AiStreamCallback() {
                    @Override
                    public void onToken(String fragment) {
                        currentResponseText.append(fragment);
                        String display = stripSpecialTokens(currentResponseText.toString());
                        updateResponseBubble(display, false);
                        scrollToBottom();
                    }

                    @Override
                    public void onThinking(String fragment) {
                        addThinkingCard(fragment);
                    }

                    @Override
                    public void onToolCall(AiToolCall toolCall) {
                        Platform.runLater(() -> {
                            if (currentResponseText.isEmpty()) {
                                messageList.getChildren().removeIf(n ->
                                    n instanceof VBox vb && vb.getChildren().stream()
                                        .anyMatch(c -> c instanceof WebView));
                                // Drop the now-detached placeholder WebViews
                                // from the re-render registry so theme-change
                                // doesn't waste a loadContent on them.
                                reRenderables.removeIf(rr ->
                                    rr.webView.getParent() == null);
                            }
                            addToolCallCard(toolCall);
                            currentResponseText = new StringBuilder();
                            currentResponseView = addAssistantBubble();
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
                            updateResponseBubble(display, true);
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

        // ── Slash command handling ──────────────────────────────

        private void handleSlashCommand(String input) {
            SlashCommandHandler.Result result = SlashCommandHandler.handle(input);

            switch (result.mode()) {
                case LIST -> addCommandResultCard(result.message());
                case HELP -> {
                    if (result.tool() != null) {
                        addCommandResultCard(result.message());
                    } else {
                        addCommandResultCard(result.message());
                    }
                }
                case DIRECT -> executeSlashDirect(result.tool(), result.args());
                case GUIDED_MODEL -> executeSlashGuided(result.tool(), result.message());
            }
        }

        /**
         * Direct tool execution from a slash command — no model inference needed.
         */
        private void executeSlashDirect(AiTool tool, Map<String, Object> args) {
            // Show the user's command
            addSystemMessage("⚡ /" + tool.getName() + " " + formatArgs(args));

            // Show tool call card
            AiToolCall tc = AiToolCall.of(tool.getName(), args);
            addToolCallCard(tc);

            // Execute synchronously on a background thread
            Thread.ofVirtual().start(() -> {
                AiToolResult execResult = ToolExecutor.execute(tool.getName(), args);
                Platform.runLater(() -> {
                    addToolResultCard(execResult);
                    scrollToBottom();
                });
            });
        }

        /**
         * Guided model execution — constrains the model to only see the specified tool,
         * then asks the model to extract parameters from the user's natural language input.
         */
        private void executeSlashGuided(AiTool tool, String rawArgs) {
            if (aiService == null || !aiService.isReady()) {
                addSystemMessage(I18n.get("builtin.ai.noModelConfigured"));
                return;
            }

            addSystemMessage("🔍 " + tool.getName() + " — asking model to extract parameters…");

            String guidedMessage = "Use the tool `" + tool.getName() + "` to handle this request: " + rawArgs;
            history.add(AiChatMessage.user(guidedMessage));

            addUserMessage("/" + tool.getName() + " " + rawArgs, List.of());

            currentResponseText = new StringBuilder();
            currentResponseView = addAssistantBubble();

            generating = true;
            sendBtn.setDisable(true);
            attachBtn.setDisable(true);
            statusLabel.setText(I18n.get("builtin.ai.generating"));

            float temperature = ZhiFlowSettingUi.getAiTemperature();
            float topP = ZhiFlowSettingUi.getAiTopP();
            int maxTokens = ZhiFlowSettingUi.getAiMaxTokens();

            // Constrain the model to only see this one tool.
            // The constraint must stay active until the async generation completes,
            // so it is cleared in onComplete/onError, not in a finally block here.
            AiServiceProvider.setConstrainedTool(tool.getName());
            try {
                aiService.chat(history, temperature, topP, maxTokens, new AiStreamCallback() {
                    @Override
                    public void onToken(String fragment) {
                        currentResponseText.append(fragment);
                        String display = stripSpecialTokens(currentResponseText.toString());
                        updateResponseBubble(display, false);
                        scrollToBottom();
                    }

                    @Override
                    public void onThinking(String fragment) {
                        addThinkingCard(fragment);
                    }

                    @Override
                    public void onToolCall(AiToolCall toolCall) {
                        Platform.runLater(() -> {
                            if (currentResponseText.isEmpty()) {
                                messageList.getChildren().removeIf(n ->
                                    n instanceof VBox vb && vb.getChildren().stream()
                                        .anyMatch(c -> c instanceof WebView));
                                // Drop the now-detached placeholder WebViews
                                // from the re-render registry so theme-change
                                // doesn't waste a loadContent on them.
                                reRenderables.removeIf(rr ->
                                    rr.webView.getParent() == null);
                            }
                            addToolCallCard(toolCall);
                            currentResponseText = new StringBuilder();
                            currentResponseView = addAssistantBubble();
                            scrollToBottom();
                        });
                    }

                    @Override
                    public void onToolResult(String toolCallId, AiToolResult execResult) {
                        Platform.runLater(() -> {
                            addToolResultCard(execResult);
                            scrollToBottom();
                        });
                    }

                    @Override
                    public void onComplete(String fullResponse, int tokensGenerated, double tokensPerSecond) {
                        AiServiceProvider.clearConstrainedTool();
                        generating = false;
                        sendBtn.setDisable(false);
                        attachBtn.setDisable(false);
                        statusLabel.setText(String.format("%d tokens · %.1f tok/s", tokensGenerated, tokensPerSecond));
                        if (currentResponseText != null) {
                            String display = stripSpecialTokens(currentResponseText.toString());
                            updateResponseBubble(display, true);
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        AiServiceProvider.clearConstrainedTool();
                        generating = false;
                        sendBtn.setDisable(false);
                        attachBtn.setDisable(false);
                        statusLabel.setText("Error: " + error.getMessage());
                        addSystemMessage("Error: " + error.getMessage());
                    }
                });
            } catch (Exception e) {
                AiServiceProvider.clearConstrainedTool();
                generating = false;
                sendBtn.setDisable(false);
                attachBtn.setDisable(false);
                statusLabel.setText("Error: " + e.getMessage());
            }
        }

        /**
         * Displays a slash command result (tool list, help text) in a formatted card.
         */
        private void addCommandResultCard(String text) {
            Label label = new Label("🔧");
            label.setStyle("-fx-text-fill: #74c0fc; -fx-font-size: 13px;");

            Label content = new Label(text);
            content.setWrapText(true);
            content.setMaxWidth(540);
            content.getStyleClass().add("sk-t1");
            content.setStyle(
                "-fx-font-size: 12.5px; " +
                "-fx-font-family: 'Menlo', 'Consolas', monospace;");

            VBox body = new VBox(4, label, content);

            HBox card = new HBox(body);
            card.setStyle(
                "-fx-background-color: rgba(116,192,252,0.08);" +
                "-fx-border-color: rgba(116,192,252,0.18);" +
                "-fx-border-width: 1px; -fx-border-radius: 10px; -fx-background-radius: 10px;" +
                "-fx-padding: 10 16 10 16;");
            card.setMaxWidth(560);

            VBox wrapper = new VBox(card);
            wrapper.setAlignment(Pos.CENTER_LEFT);
            wrapper.setPadding(new Insets(4, 0, 4, 0));

            messageList.getChildren().add(wrapper);
            scrollToBottom();
        }

        private String formatArgs(Map<String, Object> args) {
            if (args == null || args.isEmpty()) return "";
            return args.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(" "));
        }

        // ── Prompt building ────────────────────────────────────

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
            ((Label) bubble.getChildren().get(0)).getStyleClass().add("sk-t3");
            ((Label) bubble.getChildren().get(0)).setStyle(
                "-fx-font-size: 12px; -fx-alignment: center;");

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
                    chip.getStyleClass().add("sk-t1");
                    chip.setStyle(
                        "-fx-background-color: rgba(53,116,240,0.15);" +
                        "-fx-border-color: rgba(53,116,240,0.25);" +
                        "-fx-border-width: 1px; -fx-border-radius: 10px; -fx-background-radius: 10px;" +
                        "-fx-font-size: 11px; -fx-padding: 3 8 3 8;"
                    );
                    chips.getChildren().add(chip);
                }
                wrapper.getChildren().add(chips);
            }

            if (!text.isEmpty()) {
                Label bubble = new Label(text);
                bubble.getStyleClass().add("ai-msg-bubble");
                bubble.getStyleClass().add("sk-t1");
                bubble.setWrapText(true);
                bubble.setMaxWidth(560);
                bubble.setStyle(
                    "-fx-background-color: rgba(53,116,240,0.22);" +
                    "-fx-border-color: rgba(53,116,240,0.18);" +
                    "-fx-border-width: 1px; -fx-border-radius: 14px; -fx-background-radius: 14px;" +
                    "-fx-font-size: 13.5px; -fx-padding: 10 16 10 16;"
                );
                wrapper.getChildren().add(bubble);
            }

            messageList.getChildren().add(wrapper);
            scrollToBottom();
        }

        private WebView addAssistantBubble() {
            Label label = new Label("ZhiFlowClaw");
            label.getStyleClass().add("ai-msg-label");

            WebView webView = new WebView();
            webView.setMaxWidth(560);
            webView.setPrefWidth(560);
            webView.setMinHeight(24);
            webView.setPrefHeight(24);
            applyAssistantBubbleStyle(webView);
            webView.getEngine().loadContent(MarkdownRenderer.renderPlain("●●●"));
            autoResizeWebView(webView);
            // Track for theme-change re-render. The placeholder markdown + kind
            // are updated in updateResponseBubble() once the real response streams in.
            ReRenderable rr = new ReRenderable(webView, "●●●", RenderKind.PLAIN, null);
            reRenderables.add(rr);

            FadeTransition blink = new FadeTransition(Duration.millis(800), webView);
            blink.setFromValue(1.0);
            blink.setToValue(0.3);
            blink.setAutoReverse(true);
            blink.setCycleCount(Animation.INDEFINITE);
            blink.play();
            webView.setUserData(blink);

            VBox wrapper = new VBox(4, label, webView);
            wrapper.getStyleClass().add("ai-msg-assistant");
            wrapper.setAlignment(Pos.CENTER_LEFT);
            wrapper.setPadding(new Insets(2, 0, 2, 0));

            messageList.getChildren().add(wrapper);
            currentAssistantWrapper = wrapper;
            scrollToBottom();
            return webView;
        }

        /**
         * Inserts a collapsed "thinking" card (model reasoning) just above the
         * current assistant bubble. Called once per completed {@code <think>} block.
         */
        private void addThinkingCard(String thinkingMarkdown) {
            Platform.runLater(() -> {
                Label label = new Label("💭 " + I18n.get("builtin.ai.thinking"));
                label.getStyleClass().add("sk-t2");
                label.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");

                WebView wv = new WebView();
                wv.setMaxWidth(560);
                wv.setPrefWidth(560);
                wv.setMinHeight(24);
                wv.setPrefHeight(24);
                applyThinkingCardStyle(wv);
                String thinkingTitle = I18n.get("builtin.ai.thinkingSummary");
                wv.getEngine().loadContent(
                    MarkdownRenderer.renderCollapsible(thinkingTitle, thinkingMarkdown));
                autoResizeWebView(wv);
                reRenderables.add(new ReRenderable(wv, thinkingMarkdown, RenderKind.COLLAPSIBLE, thinkingTitle));

                VBox wrapper = new VBox(3, label, wv);
                wrapper.setAlignment(Pos.CENTER_LEFT);
                wrapper.setPadding(new Insets(2, 0, 2, 0));

                int idx = (currentAssistantWrapper == null)
                    ? messageList.getChildren().size()
                    : messageList.getChildren().indexOf(currentAssistantWrapper);
                messageList.getChildren().add(Math.max(0, idx), wrapper);
                scrollToBottom();
            });
        }

        /**
         * Computes the WebView container background color for the current theme.
         * Dark uses the original {@code #1e1e2e}; light uses {@code #ffffff} so
         * the area around each rendered markdown bubble matches the page.
         */
        private static String webviewBg() {
            return (ThemeService.current() == ThemeService.Theme.LIGHT) ? "#ffffff" : "#1e1e2e";
        }

        /**
         * Computes the WebView container border color for the current theme, matching
         * the {@code -sk-border} token (dark {@code #3C3F41}, light {@code #DADCE0}) so
         * bubble outlines stay visible in light theme (the old rgba(255,255,255,…)
         * was invisible on the white background).
         */
        private static String webviewBorder() {
            return (ThemeService.current() == ThemeService.Theme.LIGHT) ? "#DADCE0" : "#3C3F41";
        }

        /** Applies the assistant-bubble container style with theme-driven bg + border. */
        private static void applyAssistantBubbleStyle(WebView webView) {
            webView.setStyle(
                "-fx-background-color: " + webviewBg() + ";" +
                "-fx-border-color: " + webviewBorder() + ";" +
                "-fx-border-width: 1px; -fx-border-radius: 14px; -fx-background-radius: 14px;"
            );
        }

        /** Applies the thinking-card container style with theme-driven bg + border. */
        private static void applyThinkingCardStyle(WebView webView) {
            webView.setStyle(
                "-fx-background-color: " + webviewBg() + ";" +
                "-fx-border-color: " + webviewBorder() + ";" +
                "-fx-border-width: 1px; -fx-border-radius: 12px; -fx-background-radius: 12px;"
            );
        }

        /**
         * Re-renders the whole conversation in the current theme. Called on
         * {@link ThemeService} change. Iterates every tracked WebView, re-applies
         * the theme-driven container style, and reloads content via
         * {@link MarkdownRenderer} (which itself reads the current theme).
         */
        private void rerenderConversation() {
            for (ReRenderable rr : reRenderables) {
                // Re-apply container bg so the area around the WebView flips too.
                if (rr.kind == RenderKind.COLLAPSIBLE) {
                    applyThinkingCardStyle(rr.webView);
                } else {
                    applyAssistantBubbleStyle(rr.webView);
                }
                String html = switch (rr.kind) {
                    case FULL         -> MarkdownRenderer.render(rr.rawMarkdown);
                    case PLAIN        -> MarkdownRenderer.renderPlain(rr.rawMarkdown);
                    case COLLAPSIBLE  -> MarkdownRenderer.renderCollapsible(rr.collapsibleTitle, rr.rawMarkdown);
                };
                rr.webView.getEngine().loadContent(html);
            }
        }

        private void autoResizeWebView(WebView webView) {
            webView.getEngine().getLoadWorker().stateProperty().addListener((obs, old, state) -> {
                if (state == javafx.concurrent.Worker.State.SUCCEEDED) {
                    Object result = webView.getEngine().executeScript(
                        "document.body.scrollHeight || document.documentElement.scrollHeight"
                    );
                    if (result instanceof Number height) {
                        Platform.runLater(() -> webView.setPrefHeight(
                            Math.max(24, height.doubleValue() + 4)
                        ));
                    }
                }
            });
        }

        private void updateResponseBubble(String displayText, boolean isFinal) {
            Platform.runLater(() -> {
                if (currentResponseView == null) return;
                if (displayText.isEmpty()) return;

                if (currentResponseView.getUserData() instanceof Animation anim) {
                    anim.stop();
                    currentResponseView.setUserData(null);
                }

                String html = isFinal
                    ? MarkdownRenderer.render(displayText)
                    : MarkdownRenderer.renderPlain(displayText);
                currentResponseView.getEngine().loadContent(html);

                // Keep the re-render registry in sync: streaming calls use
                // renderPlain, the final call switches to full Markdown render.
                // On theme change rerenderConversation() reads these so the new
                // theme shows the same content at the same fidelity.
                for (ReRenderable rr : reRenderables) {
                    if (rr.webView == currentResponseView) {
                        rr.rawMarkdown = displayText;
                        rr.kind = isFinal ? RenderKind.FULL : RenderKind.PLAIN;
                        break;
                    }
                }
            });
        }

        private void addToolCallCard(AiToolCall toolCall) {
            Label icon = new Label("⚙");
            icon.setStyle("-fx-text-fill: #f59f00; -fx-font-size: 13px;");

            Label name = new Label(I18n.get("builtin.ai.calling", toolCall.name()));
            name.getStyleClass().add("sk-t1");
            name.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");

            String argsStr = toolCall.arguments().entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining(", "));

            Label args = new Label(argsStr);
            args.setWrapText(true);
            args.setMaxWidth(480);
            args.getStyleClass().add("sk-t2");
            args.setStyle("-fx-font-size: 11px;");

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
            label.getStyleClass().add("sk-t2");
            label.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");

            Label output = new Label(result.output());
            output.setWrapText(true);
            output.setMaxWidth(480);
            output.getStyleClass().add("sk-t2");
            output.setStyle("-fx-font-size: 11px;");

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
