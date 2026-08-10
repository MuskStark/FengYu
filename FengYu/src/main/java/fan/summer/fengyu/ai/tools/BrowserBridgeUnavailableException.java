package fan.summer.fengyu.ai.tools;

/** Thrown when the Electron browser bridge is unreachable, returns non-200, or times out. */
class BrowserBridgeUnavailableException extends RuntimeException {
    BrowserBridgeUnavailableException(String message) { super(message); }
    BrowserBridgeUnavailableException(String message, Throwable cause) { super(message, cause); }
}
