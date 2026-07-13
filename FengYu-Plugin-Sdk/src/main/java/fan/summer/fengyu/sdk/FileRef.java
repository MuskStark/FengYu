package fan.summer.fengyu.sdk;

/** UI-side opaque file capability shape, useful when sharing DTOs or schemas. */
public record FileRef(String id, String name, String kind, String access, long size) {}
