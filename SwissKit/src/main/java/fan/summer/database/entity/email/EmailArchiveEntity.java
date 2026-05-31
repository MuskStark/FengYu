package fan.summer.database.entity.email;

import lombok.Data;

import java.util.Date;

@Data
public class EmailArchiveEntity {
    private Integer id;
    private String accountEmail;
    private String folder;
    private String messageUid;
    private String subject;
    private String fromAddress;
    private String toAddress;
    private String ccAddress;
    private Date sendDate;
    private Boolean hasAttachment;
    private String emlPath;
    private String bodyPreview;
    private Date archivedAt;
}
