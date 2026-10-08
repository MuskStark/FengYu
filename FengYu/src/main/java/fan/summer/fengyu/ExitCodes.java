package fan.summer.fengyu;

/**
 * Process exit codes used by the backend to coordinate with the desktop (Electron) sidecar
 * supervisor (or Web deployment restart logic). A fatal startup failure needs no constant:
 * an uncaught exception out of {@link HeadlessLauncher#main} already exits the JVM with
 * status 1, which is the contract the supervisor treats as fatal.
 */
public final class ExitCodes {

    private ExitCodes() {}

    /** Setup wizard completed successfully — parent process should restart into APP mode. */
    public static final int SETUP_DONE = 0;
}
