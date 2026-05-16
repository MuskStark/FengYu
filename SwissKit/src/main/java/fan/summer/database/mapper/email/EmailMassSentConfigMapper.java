package fan.summer.database.mapper.email;

import fan.summer.database.entity.email.EmailMassSentConfigEntity;

import java.util.List;

public interface EmailMassSentConfigMapper {
    void upsert(EmailMassSentConfigEntity config);
    EmailMassSentConfigEntity selectByTaskId(String taskId);
    List<EmailMassSentConfigEntity> selectAll();
    void deleteByTaskId(String taskId);
    void deleteAll();
}
