package fan.summer.fengyu.plugin.email.model;

import java.time.LocalDateTime;
import java.util.Set;

public record Contact(long id, String email, String nickname, LocalDateTime createdAt, Set<Long> tagIds) {
    public Contact {
        tagIds = tagIds == null ? Set.of() : Set.copyOf(tagIds);
    }
}
