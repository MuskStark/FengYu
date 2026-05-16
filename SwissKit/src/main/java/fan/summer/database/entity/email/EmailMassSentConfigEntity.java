package fan.summer.database.entity.email;

import lombok.Data;

@Data
public class EmailMassSentConfigEntity {
    private Long id;
    private String taskId;
    private String toTag;
    private String ccTag;
    private boolean isSentAtt;
    private String attFolderPath;
}
