package fan.summer.fengyu.plugin.email.model;

import java.time.LocalDateTime;

public record MassConfig(long id, String name, String mode, String configJson, LocalDateTime createdAt) {
}
