package fan.summer.fengyu.plugin.email.model;

import java.nio.file.Path;
import java.util.List;

/** Immutable input for one SMTP message. */
public record EmailMessageRequest(long accountId, List<String> to, List<String> cc, List<String> bcc,
        String subject, String plainText, String htmlText, List<Path> attachments) {
    public EmailMessageRequest {
        to = copy(to);
        cc = copy(cc);
        bcc = copy(bcc);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
