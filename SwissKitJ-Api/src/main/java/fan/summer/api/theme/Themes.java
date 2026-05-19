package fan.summer.api.theme;

import javafx.scene.Scene;

/**
 * SwissKitJ 主题样式加载工具。
 *
 * <p>主程序在 Scene 上加载 {@link #commonStylesheetUrl()} 后，所有嵌入主 Scene 的
 * 插件 Node 都自动继承共性样式。仅当插件自建独立 Stage / Scene 时才需要
 * 调用 {@link #applyTo(Scene)} 主动加载。</p>
 */
public final class Themes {

    /** 共性样式表资源路径（位于本 jar 中）。 */
    public static final String COMMON_CSS = "/css/swisskit-common.css";

    private Themes() {}

    /** 返回共性样式表的外部 URL，可直接放入 {@code Scene#getStylesheets()}。 */
    public static String commonStylesheetUrl() {
        return Themes.class.getResource(COMMON_CSS).toExternalForm();
    }

    /** 对独立 Stage（弹窗 / 插件自带 Scene）一键应用基础主题，已加载则忽略。 */
    public static void applyTo(Scene scene) {
        if (scene == null) return;
        String url = commonStylesheetUrl();
        if (!scene.getStylesheets().contains(url)) {
            scene.getStylesheets().add(url);
        }
    }
}
