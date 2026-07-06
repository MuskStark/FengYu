package fan.summer.api.preview;

import fan.summer.api.MdiIconUtil;
import fan.summer.api.i18n.I18n;
import fan.summer.api.theme.ThemeService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import java.util.Locale;

/**
 * Title bar for the preview window. Shows a title label, a language toggle
 * (中文 / English), a dark/light theme toggle, and a close button.
 * Supports drag-to-move.
 *
 * <p>The theme toggle calls {@link ThemeService#set(ThemeService.Theme)},
 * which re-stamps the theme class on every registered scene (the preview
 * scene is registered via {@code Themes.applyTo}), so the whole window
 * flips with no per-component wiring. The language toggle calls
 * {@link I18n#setLocale(Locale)}; {@link PreviewShell} listens and
 * re-applies all text.</p>
 */
class PreviewTitleBar extends HBox {

    private double dragX, dragY;

    private final Label titleLbl;
    private final Label langBtn;
    private final Label themeBtn;
    private final Label closeBtn;
    private Text themeIcon;

    PreviewTitleBar(String title) {
        getStyleClass().add("preview-titlebar");
        setAlignment(Pos.CENTER_LEFT);

        titleLbl = new Label(title);
        titleLbl.getStyleClass().add("preview-titlebar-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        langBtn = buildIconButton("translate", "preview.toggle.language");
        langBtn.setOnMouseClicked(e -> toggleLanguage());

        themeBtn = buildIconButton(currentThemeIcon(), "preview.toggle.theme");
        themeIcon = (Text) themeBtn.getGraphic();
        themeBtn.setOnMouseClicked(e -> toggleTheme());

        closeBtn = new Label("✕");
        closeBtn.getStyleClass().add("preview-titlebar-close");
        closeBtn.setPadding(new Insets(4, 8, 4, 8));
        closeBtn.setMinSize(28, 28);

        getChildren().addAll(titleLbl, spacer, langBtn, themeBtn, closeBtn);
        setPadding(new Insets(8, 12, 8, 16));

        // Keep the theme icon in sync regardless of where the change originated
        // (this button or the host app's own theme control).
        ThemeService.onChange(t -> Platform.runLater(() -> {
            themeIcon = MdiIconUtil.createIcon(currentThemeIcon(), 16);
            themeIcon.getStyleClass().add("preview-titlebar-btn-icon");
            themeBtn.setGraphic(themeIcon);
        }));

        applyLocale();
    }

    private Label buildIconButton(String mdiIcon, String tooltipKey) {
        Label btn = new Label();
        Text icon = MdiIconUtil.createIcon(mdiIcon, 16);
        icon.getStyleClass().add("preview-titlebar-btn-icon");
        btn.setGraphic(icon);
        btn.getStyleClass().add("preview-titlebar-btn");
        btn.setPadding(new Insets(4, 8, 4, 8));
        btn.setMinSize(28, 28);
        btn.setTooltip(new Tooltip());
        btn.getProperties().put("tooltipKey", tooltipKey);
        return btn;
    }

    private void toggleTheme() {
        ThemeService.Theme next = (ThemeService.current() == ThemeService.Theme.DARK)
            ? ThemeService.Theme.LIGHT : ThemeService.Theme.DARK;
        ThemeService.set(next);
    }

    private void toggleLanguage() {
        Locale next = (I18n.getLocale().getLanguage().equals(Locale.SIMPLIFIED_CHINESE.getLanguage()))
            ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE;
        I18n.setLocale(next);
    }

    /** Refresh button tooltips on locale change (called by PreviewShell's i18n listener). */
    void applyLocale() {
        applyTooltip(langBtn, "preview.toggle.language");
        applyTooltip(themeBtn, "preview.toggle.theme");
    }

    private void applyTooltip(Label btn, String key) {
        if (btn.getTooltip() != null) btn.getTooltip().setText(I18n.get(key));
    }

    private static String currentThemeIcon() {
        return ThemeService.current() == ThemeService.Theme.DARK ? "weather-night" : "weather-sunny";
    }

    /** Wire the close button and drag-to-move after the Stage is available. */
    void bindStage(Stage stage, Runnable onClose) {
        closeBtn.setOnMouseClicked(e -> {
            stage.close();
            if (onClose != null) onClose.run();
        });

        setOnMousePressed(e -> {
            dragX = e.getScreenX() - stage.getX();
            dragY = e.getScreenY() - stage.getY();
        });
        setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - dragX);
            stage.setY(e.getScreenY() - dragY);
        });
    }
}
