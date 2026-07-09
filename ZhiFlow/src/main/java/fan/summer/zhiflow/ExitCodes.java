package fan.summer.zhiflow;

/**
 * Process exit codes used by {@link HeadlessLauncher} to coordinate with the Tauri sidecar
 * supervisor (or Web deployment restart logic).
 */
public final class ExitCodes {

    private ExitCodes() {}

    /** Setup wizard completed successfully — parent process should restart into APP mode. */
    public static final int SETUP_DONE = 0;

    /** Fatal startup error. */
    public static final int FATAL = 1;
}
