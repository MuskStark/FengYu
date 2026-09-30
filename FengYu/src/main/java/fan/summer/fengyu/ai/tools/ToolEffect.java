package fan.summer.fengyu.ai.tools;

/** User-visible effect class used by the permission policy and tool activity timeline. */
public enum ToolEffect {
    READ("read"), WRITE("write"), COMMAND("command"), EXTERNAL("external");
    private final String id;
    ToolEffect(String id) { this.id = id; }
    public String id() { return id; }
    public static ToolEffect from(String value) {
        if (value != null) for (ToolEffect effect : values()) if (effect.id.equals(value)) return effect;
        return EXTERNAL;
    }
}
