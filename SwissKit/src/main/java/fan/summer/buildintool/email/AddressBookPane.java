package fan.summer.buildintool.email;

import fan.summer.api.component.SkNotification;
import fan.summer.api.i18n.I18n;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import fan.summer.api.theme.Themes;
import fan.summer.database.DatabaseInit;
import fan.summer.database.entity.setting.email.EmailAddressBookEntity;
import fan.summer.database.entity.setting.email.EmailTagEntity;
import fan.summer.database.mapper.setting.email.EmailAddressBookMapper;
import fan.summer.database.mapper.setting.email.EmailTagMapper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.apache.ibatis.session.SqlSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only address book side panel for embedding in the Email tool view.
 *
 * <p>Contacts are loaded from the database on construction and grouped by their
 * first matching tag. A refresh button at the top re-loads the data without
 * destroying the panel structure. Contacts with no tags are listed under an
 * "Untagged" section.
 *
 * <p>The panel is initially hidden and toggled visible via the address book button
 * in {@link EmailPlugin}; it uses {@code setManaged(false)} when hidden to avoid
 * consuming layout space in the parent HBox.
 *
 * @since 1.0.0
 * @see EmailPlugin
 */
public class AddressBookPane extends VBox {

    private static final PluginLogger log = LoggerFactory.getLogger(AddressBookPane.class);
    private static final Pattern NUMERIC = Pattern.compile("\\d+");

    public AddressBookPane() {
        log.debug("Initializing AddressBookPane");
        setSpacing(8);
        setPadding(new Insets(12));
        getStyleClass().add("sk-surface");
        setStyle("-fx-background-radius: 10;");
        setPrefWidth(240);
        setMinWidth(200);

        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label(I18n.get("builtin.email.addressBook"));
        title.getStyleClass().add("sk-t1");
        title.setStyle("-fx-font-size: 13px; -fx-font-weight: 600;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button refreshBtn = new Button("↻");
        refreshBtn.getStyleClass().add("sk-t2");
        refreshBtn.setStyle("-fx-background-color: transparent; -fx-font-size: 14px; -fx-cursor: hand; -fx-padding: 2 6 2 6;");
        refreshBtn.setOnAction(e -> loadContacts());
        header.getChildren().addAll(title, spacer, refreshBtn);

        getChildren().add(header);
        loadContacts();
    }

    private void loadContacts() {
        log.debug("Loading contacts from database");
        Thread loaderThread = new Thread(() -> {
            List<EmailTagEntity> tags;
            List<EmailAddressBookEntity> addresses;
            try (SqlSession session = DatabaseInit.getSqlSession()) {
                tags = session.getMapper(EmailTagMapper.class).selectAll();
                if (tags == null) tags = new ArrayList<>();
                addresses = session.getMapper(EmailAddressBookMapper.class).selectEmailAddressBook();
                if (addresses == null) addresses = new ArrayList<>();
                log.debug("Loaded {} tags and {} addresses from database", tags.size(), addresses.size());
            } catch (Exception e) {
                log.error("Failed to load contacts from database: {}", e.getMessage());
                return;
            }

            Map<Long, String> tagNameMap = new HashMap<>();
            for (EmailTagEntity t : tags) tagNameMap.put(t.getId(), t.getTag());

            // Group contacts by tag
            Map<String, List<String>> byTag = new HashMap<>();
            List<String> untagged = new ArrayList<>();
            for (EmailAddressBookEntity addr : addresses) {
                String display = addr.getNickname() != null && !addr.getNickname().isBlank()
                        ? addr.getNickname() + " <" + addr.getEmailAddress() + ">"
                        : addr.getEmailAddress();
                if (addr.getTags() == null || addr.getTags().isBlank()) {
                    untagged.add(display);
                    continue;
                }
                Matcher m = NUMERIC.matcher(addr.getTags());
                boolean found = false;
                while (m.find()) {
                    try {
                        long id = Long.parseLong(m.group());
                        String name = tagNameMap.get(id);
                        if (name != null) {
                            byTag.computeIfAbsent(name, k -> new ArrayList<>()).add(display);
                            found = true;
                        }
                    } catch (NumberFormatException ignored) {}
                }
                if (!found) untagged.add(display);
            }

            log.debug("Built address book view: {} tagged groups, {} untagged contacts", byTag.size(), untagged.size());
            Platform.runLater(() -> buildView(byTag, untagged));
        });
        loaderThread.setName("address-book-load");
        loaderThread.setDaemon(true);
        loaderThread.start();
    }

    private void buildView(Map<String, List<String>> byTag, List<String> untagged) {
        // Remove everything after the header
        if (getChildren().size() > 1) {
            getChildren().remove(1, getChildren().size());
        }

        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        scroll.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox content = new VBox(10);
        content.setStyle("-fx-background-color: transparent;");

        for (Map.Entry<String, List<String>> entry : byTag.entrySet()) {
            Label tagLabel = new Label(entry.getKey());
            tagLabel.setStyle("-fx-text-fill: #3574F0; -fx-font-size: 11px; -fx-font-weight: bold;");
            content.getChildren().add(tagLabel);

            for (String contact : entry.getValue()) {
                Label contactLabel = new Label("  " + contact);
                contactLabel.getStyleClass().add("sk-t2");
                contactLabel.setStyle("-fx-font-size: 11px;");
                contactLabel.setWrapText(true);
                contactLabel.setMaxWidth(Double.MAX_VALUE);
                content.getChildren().add(contactLabel);
            }
        }

        if (!untagged.isEmpty()) {
            Label untaggedLabel = new Label(I18n.get("builtin.email.untagged"));
            untaggedLabel.getStyleClass().add("sk-t2");
            untaggedLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
            content.getChildren().add(untaggedLabel);
            for (String contact : untagged) {
                Label contactLabel = new Label("  " + contact);
                contactLabel.getStyleClass().add("sk-t2");
                contactLabel.setStyle("-fx-font-size: 11px;");
                contactLabel.setWrapText(true);
                contactLabel.setMaxWidth(Double.MAX_VALUE);
                content.getChildren().add(contactLabel);
            }
        }

        if (byTag.isEmpty() && untagged.isEmpty()) {
            Label empty = new Label(I18n.get("builtin.email.noContacts"));
            empty.getStyleClass().add("sk-t3");
            empty.setStyle("-fx-font-size: 11px;");
            content.getChildren().add(empty);
        }

        scroll.setContent(content);
        getChildren().add(scroll);
    }
}
