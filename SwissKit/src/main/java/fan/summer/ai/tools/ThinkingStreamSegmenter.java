package fan.summer.ai.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * Stateful stream segmenter for Qwen3 hybrid-reasoning output.
 *
 * <p>Splits the raw token stream into three regions:
 * <ul>
 *   <li>{@code <think>…</think>} — routed to the thinking display ({@link Type#THINK})</li>
 *   <li>{@code <tool_call>…</tool_call>} — suppressed from display entirely</li>
 *   <li>plain content — the visible answer ({@link Type#CONTENT})</li>
 * </ul>
 *
 * <p>Markers may be split across token boundaries (the model emits {@code "<thi"} then
 * {@code "nk>"}). When no full marker is present, the segmenter holds back the tail of
 * the buffer if it could be the prefix of a marker, so a half marker is never emitted
 * as content. This mirrors {@link fan.summer.ai.inference.StopDetector#endsWithPartialStop}.
 *
 * <p>The segmenter is single-use per generation round. Call {@link #flush()} at EOS to
 * drain any trailing content / unclosed think block.
 */
public final class ThinkingStreamSegmenter {

    /** Displayable segment type. Tool-call regions produce no segment at all. */
    public enum Type { THINK, CONTENT }

    public record Segment(Type type, String text) {}

    private static final String THINK_OPEN  = "<think>";
    private static final String THINK_CLOSE = "</think>";
    private static final String CALL_OPEN   = "<tool_call>";
    private static final String CALL_CLOSE  = "</tool_call>";
    private static final String[] MARKERS = { THINK_OPEN, THINK_CLOSE, CALL_OPEN, CALL_CLOSE };
    private static final int MAX_MARKER;
    static {
        int m = 0;
        for (String mk : MARKERS) if (mk.length() > m) m = mk.length();
        MAX_MARKER = m;
    }

    private final StringBuilder pending = new StringBuilder();
    private boolean inThink = false;
    private boolean inToolCall = false;

    /**
     * Feed one token fragment; returns the displayable segments produced by this token
     * (possibly empty — the token may have been held back or fallen inside a region).
     */
    public List<Segment> feed(String fragment) {
        if (fragment == null || fragment.isEmpty()) return List.of();
        pending.append(fragment);
        return scan();
    }

    /**
     * Drain pending state at end-of-stream. An unclosed {@code <think>} is emitted as a
     * THINK segment; an unclosed {@code <tool_call>} is discarded; trailing content is
     * emitted as CONTENT.
     */
    public List<Segment> flush() {
        List<Segment> out = new ArrayList<>();
        if (inThink) {
            out.add(new Segment(Type.THINK, pending.toString()));
        } else if (!inToolCall && !pending.isEmpty()) {
            out.add(new Segment(Type.CONTENT, pending.toString()));
        }
        pending.setLength(0);
        inThink = false;
        inToolCall = false;
        return out;
    }

    private List<Segment> scan() {
        List<Segment> out = new ArrayList<>();
        while (true) {
            if (inToolCall) {
                int close = pending.indexOf(CALL_CLOSE);
                if (close < 0) break;                       // keep buffering the suppressed region
                pending.delete(0, close + CALL_CLOSE.length());
                inToolCall = false;
                continue;
            }
            if (inThink) {
                int close = pending.indexOf(THINK_CLOSE);
                if (close < 0) break;                       // keep buffering think text
                out.add(new Segment(Type.THINK, pending.substring(0, close)));
                pending.delete(0, close + THINK_CLOSE.length());
                inThink = false;
                continue;
            }
            int tOpen = pending.indexOf(THINK_OPEN);
            int cOpen = pending.indexOf(CALL_OPEN);
            int next = minPositive(tOpen, cOpen);
            if (next < 0) {
                int hold = longestMarkerPrefix(pending);
                int emitLen = pending.length() - hold;
                if (emitLen > 0) {
                    out.add(new Segment(Type.CONTENT, pending.substring(0, emitLen)));
                    pending.delete(0, emitLen);
                }
                break;
            }
            if (next > 0) {
                out.add(new Segment(Type.CONTENT, pending.substring(0, next)));
                pending.delete(0, next);
            }
            if (next == tOpen) {
                pending.delete(0, THINK_OPEN.length());
                inThink = true;
            } else {
                pending.delete(0, CALL_OPEN.length());
                inToolCall = true;
            }
        }
        return out;
    }

    /** Largest k such that the buffer's tail of length k is a proper prefix of some marker. */
    private static int longestMarkerPrefix(StringBuilder buf) {
        int len = buf.length();
        int limit = Math.min(MAX_MARKER - 1, len);
        int best = 0;
        for (int k = limit; k >= 1; k--) {
            String tail = buf.substring(len - k, len);
            for (String m : MARKERS) {
                if (k < m.length() && tail.equals(m.substring(0, k))) {
                    best = Math.max(best, k);
                    break;
                }
            }
        }
        return best;
    }

    private static int minPositive(int a, int b) {
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }
}
