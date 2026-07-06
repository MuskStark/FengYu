package fan.summer.zhiflow.buildintool.emailarchive;

import lombok.Data;

import java.nio.file.Path;
import java.time.LocalDate;

@Data
public class EmailArchiveConfig {
    private String accountEmail;
    private String imapFolder = "INBOX";
    private int days = 30;
    private LocalDate startDate;
    private LocalDate endDate;
    private Path outputDir;
}
