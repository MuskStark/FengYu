package fan.summer.fengyu.ai.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Name-to-{@code KeyEvent} VK mapping for {@code computer_key} combos and
 * {@code computer_type} keystroke typing. Pure parsing — no AWT interaction — so it is
 * unit-testable on headless CI.
 *
 * <p>Modifier aliases are deliberately generous ({@code cmd}, {@code command}, {@code meta},
 * {@code win}, {@code super} all mean the platform command key; {@code controlorcontext} maps
 * like the browser tool's {@code ControlOrMeta} convention: Command on macOS, Control
 * elsewhere). Key names are matched case-insensitively.
 */
final class ComputerKeyMap {

    private ComputerKeyMap() {}

    /** A parsed shortcut: modifier VK codes (press order) plus the final key code. */
    record KeyCombo(List<Integer> modifiers, int keyCode) {
        KeyCombo {
            modifiers = List.copyOf(modifiers);
        }
    }

    /** A single keystroke: the base VK code plus whether Shift must be held. */
    record Stroke(int keyCode, boolean shift) {}

    private static final Map<String, Integer> NAMED_KEYS = buildNamedKeys();

    /** Modifier name → VK code. {@code controlorcontext} resolves per-OS in {@link #parse}. */
    private static final Map<String, Integer> MODIFIERS = Map.ofEntries(
            Map.entry("shift", java.awt.event.KeyEvent.VK_SHIFT),
            Map.entry("ctrl", java.awt.event.KeyEvent.VK_CONTROL),
            Map.entry("control", java.awt.event.KeyEvent.VK_CONTROL),
            Map.entry("alt", java.awt.event.KeyEvent.VK_ALT),
            Map.entry("option", java.awt.event.KeyEvent.VK_ALT),
            Map.entry("opt", java.awt.event.KeyEvent.VK_ALT),
            Map.entry("meta", java.awt.event.KeyEvent.VK_META),
            Map.entry("cmd", java.awt.event.KeyEvent.VK_META),
            Map.entry("command", java.awt.event.KeyEvent.VK_META),
            Map.entry("win", java.awt.event.KeyEvent.VK_META),
            Map.entry("super", java.awt.event.KeyEvent.VK_META));

    private static final Set<String> MAC_OS = Set.of("mac", "macos", "darwin");

    private static Map<String, Integer> buildNamedKeys() {
        Map<String, Integer> keys = new LinkedHashMap<>();
        keys.put("enter", java.awt.event.KeyEvent.VK_ENTER);
        keys.put("return", java.awt.event.KeyEvent.VK_ENTER);
        keys.put("tab", java.awt.event.KeyEvent.VK_TAB);
        keys.put("escape", java.awt.event.KeyEvent.VK_ESCAPE);
        keys.put("esc", java.awt.event.KeyEvent.VK_ESCAPE);
        keys.put("backspace", java.awt.event.KeyEvent.VK_BACK_SPACE);
        keys.put("delete", java.awt.event.KeyEvent.VK_DELETE);
        keys.put("del", java.awt.event.KeyEvent.VK_DELETE);
        keys.put("space", java.awt.event.KeyEvent.VK_SPACE);
        keys.put("home", java.awt.event.KeyEvent.VK_HOME);
        keys.put("end", java.awt.event.KeyEvent.VK_END);
        keys.put("pageup", java.awt.event.KeyEvent.VK_PAGE_UP);
        keys.put("pgup", java.awt.event.KeyEvent.VK_PAGE_UP);
        keys.put("pagedown", java.awt.event.KeyEvent.VK_PAGE_DOWN);
        keys.put("pgdn", java.awt.event.KeyEvent.VK_PAGE_DOWN);
        keys.put("up", java.awt.event.KeyEvent.VK_UP);
        keys.put("down", java.awt.event.KeyEvent.VK_DOWN);
        keys.put("left", java.awt.event.KeyEvent.VK_LEFT);
        keys.put("right", java.awt.event.KeyEvent.VK_RIGHT);
        keys.put("arrowup", java.awt.event.KeyEvent.VK_UP);
        keys.put("arrowdown", java.awt.event.KeyEvent.VK_DOWN);
        keys.put("arrowleft", java.awt.event.KeyEvent.VK_LEFT);
        keys.put("arrowright", java.awt.event.KeyEvent.VK_RIGHT);
        keys.put("insert", java.awt.event.KeyEvent.VK_INSERT);
        keys.put("printscreen", java.awt.event.KeyEvent.VK_PRINTSCREEN);
        keys.put("capslock", java.awt.event.KeyEvent.VK_CAPS_LOCK);
        for (int i = 1; i <= 24; i++) keys.put("f" + i, java.awt.event.KeyEvent.VK_F1 + i - 1);
        // US-layout punctuation (unshifted and shifted forms).
        keys.put("`", java.awt.event.KeyEvent.VK_BACK_QUOTE);
        keys.put("~", java.awt.event.KeyEvent.VK_BACK_QUOTE);
        keys.put("-", java.awt.event.KeyEvent.VK_MINUS);
        keys.put("_", java.awt.event.KeyEvent.VK_MINUS);
        keys.put("=", java.awt.event.KeyEvent.VK_EQUALS);
        keys.put("+", java.awt.event.KeyEvent.VK_EQUALS);
        // Shifted digits (US layout): ! @ # $ % ^ & * ( ).
        keys.put("!", java.awt.event.KeyEvent.VK_1);
        keys.put("@", java.awt.event.KeyEvent.VK_2);
        keys.put("#", java.awt.event.KeyEvent.VK_3);
        keys.put("$", java.awt.event.KeyEvent.VK_4);
        keys.put("%", java.awt.event.KeyEvent.VK_5);
        keys.put("^", java.awt.event.KeyEvent.VK_6);
        keys.put("&", java.awt.event.KeyEvent.VK_7);
        keys.put("*", java.awt.event.KeyEvent.VK_8);
        keys.put("(", java.awt.event.KeyEvent.VK_9);
        keys.put(")", java.awt.event.KeyEvent.VK_0);
        keys.put("[", java.awt.event.KeyEvent.VK_OPEN_BRACKET);
        keys.put("{", java.awt.event.KeyEvent.VK_OPEN_BRACKET);
        keys.put("]", java.awt.event.KeyEvent.VK_CLOSE_BRACKET);
        keys.put("}", java.awt.event.KeyEvent.VK_CLOSE_BRACKET);
        keys.put("\\", java.awt.event.KeyEvent.VK_BACK_SLASH);
        keys.put("|", java.awt.event.KeyEvent.VK_BACK_SLASH);
        keys.put(";", java.awt.event.KeyEvent.VK_SEMICOLON);
        keys.put(":", java.awt.event.KeyEvent.VK_SEMICOLON);
        keys.put("'", java.awt.event.KeyEvent.VK_QUOTE);
        keys.put("\"", java.awt.event.KeyEvent.VK_QUOTE);
        keys.put(",", java.awt.event.KeyEvent.VK_COMMA);
        keys.put("<", java.awt.event.KeyEvent.VK_COMMA);
        keys.put(".", java.awt.event.KeyEvent.VK_PERIOD);
        keys.put(">", java.awt.event.KeyEvent.VK_PERIOD);
        keys.put("/", java.awt.event.KeyEvent.VK_SLASH);
        keys.put("?", java.awt.event.KeyEvent.VK_SLASH);
        return Map.copyOf(keys);
    }

    /** Punctuation that requires Shift held on a US layout, for stroke typing. */
    private static final Set<Character> SHIFTED_CHARS = Set.of(
            '~', '!', '@', '#', '$', '%', '^', '&', '*', '(', ')', '_', '+',
            '{', '}', '|', ':', '"', '<', '>', '?');

    /**
     * Parses a combo like {@code "cmd+shift+3"} or {@code "enter"}. The last token is the key;
     * every earlier token must be a modifier. Platform-dependent {@code controlorcontext}
     * resolves to Command on macOS and Control elsewhere.
     *
     * @throws IllegalArgumentException with a model-friendly message on unknown names
     */
    static KeyCombo parse(String combo, String osName) {
        if (combo == null || combo.isBlank()) {
            throw new IllegalArgumentException("key must not be empty; examples: 'enter', 'ctrl+c', 'cmd+shift+3'");
        }
        String[] tokens = combo.trim().toLowerCase(Locale.ROOT).split("\\+");
        if (tokens.length > 5) {
            throw new IllegalArgumentException("too many keys in combo (max 5): " + combo);
        }
        List<Integer> modifiers = new ArrayList<>();
        for (int i = 0; i < tokens.length - 1; i++) {
            String token = tokens[i].trim();
            if (token.isEmpty()) {
                throw new IllegalArgumentException("empty key segment in combo: " + combo);
            }
            if ("controlormeta".equals(token) || "controlorcontext".equals(token)) {
                modifiers.add(isMac(osName)
                        ? java.awt.event.KeyEvent.VK_META
                        : java.awt.event.KeyEvent.VK_CONTROL);
                continue;
            }
            Integer vk = MODIFIERS.get(token);
            if (vk == null) {
                throw new IllegalArgumentException(String.format(
                        "unknown modifier '%s' in combo '%s' (modifiers: shift, ctrl, alt, meta/cmd, controlormeta)",
                        token, combo));
            }
            modifiers.add(vk);
        }
        int keyCode = keyCodeFor(tokens[tokens.length - 1].trim());
        if (keyCode < 0) {
            throw new IllegalArgumentException(String.format(
                    "unknown key '%s' in combo '%s' (examples: enter, tab, esc, space, a-z, 0-9, f1-f12, arrows)",
                    tokens[tokens.length - 1], combo));
        }
        return new KeyCombo(modifiers, keyCode);
    }

    /** Resolves one non-modifier token to a VK code, or {@code -1} when unknown. */
    private static int keyCodeFor(String token) {
        if (token.isEmpty()) return -1;
        if (token.length() == 1) {
            char c = token.charAt(0);
            if (c >= 'a' && c <= 'z') return java.awt.event.KeyEvent.VK_A + (c - 'a');
            if (c >= '0' && c <= '9') return java.awt.event.KeyEvent.VK_0 + (c - '0');
        }
        Integer named = NAMED_KEYS.get(token);
        return named == null ? -1 : named;
    }

    /**
     * Maps one printable character to a keystroke on a US layout, or {@code null} when the
     * character cannot be typed with Robot key events (non-ASCII text, dead-key accents).
     * Callers fall back to clipboard paste for such text.
     */
    static Stroke strokeFor(char c) {
        if (c == ' ') return new Stroke(java.awt.event.KeyEvent.VK_SPACE, false);
        if (c >= 'a' && c <= 'z') return new Stroke(java.awt.event.KeyEvent.VK_A + (c - 'a'), false);
        if (c >= 'A' && c <= 'Z') return new Stroke(java.awt.event.KeyEvent.VK_A + (c - 'A'), true);
        if (c >= '0' && c <= '9') return new Stroke(java.awt.event.KeyEvent.VK_0 + (c - '0'), false);
        Integer vk = NAMED_KEYS.get(String.valueOf(c));
        if (vk != null) {
            return new Stroke(vk, SHIFTED_CHARS.contains(c));
        }
        return null;
    }

    /** True when every character of {@code text} is mappable by {@link #strokeFor(char)}. */
    static boolean typeable(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (strokeFor(text.charAt(i)) == null) return false;
        }
        return true;
    }

    static boolean isMac(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT).trim();
        return MAC_OS.stream().anyMatch(os::startsWith);
    }
}
