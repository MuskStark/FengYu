package fan.summer.fengyu.plugin.store;

/** Thrown when a pinned git sha does not match the cloned HEAD (supply-chain tamper guard). */
public class IntegrityException extends RuntimeException {
    public IntegrityException(String expected, String actual) {
        super("Integrity check failed: expected sha " + expected + " but got " + actual);
    }
}
