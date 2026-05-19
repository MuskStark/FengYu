package fan.summer.database.entity.email;

import lombok.Data;

import java.util.Date;

@Data
public class EmailSentLogEntity {
    private Long id;
    private String to;
    private String cc;
    private String bcc;
    private String subject;
    private String content;
    private String attachment;
    private Date sendTime;
    private boolean isSuccess;
}
