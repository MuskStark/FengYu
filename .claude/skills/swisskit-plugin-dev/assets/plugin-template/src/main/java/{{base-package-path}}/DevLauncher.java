package {{base-package}};

/**
 * Dev entry point for {@code mvn javafx:run -Pdev}.
 *
 * <p><b>This class must contain ZERO JavaFX imports.</b> Importing
 * {@code javafx.application.Application} here triggers
 * {@code NoClassDefFoundError: javafx/application/Application} under the JavaFX module system.
 * The module system scans the main class for JavaFX types; {@code DevLauncher} deliberately
 * references none, so {@code DevApp} (loaded after the module path is set) is where JavaFX
 * types appear.
 *
 * <p>The only job of this class is to hand off to {@link {{Name}}DevApp}.
 */
public final class DevLauncher {

    private DevLauncher() {}

    public static void main(String[] args) {
        // Delegate to the JavaFX Application subclass — do NOT import JavaFX here.
        {{Name}}DevApp.main(args);
    }
}
