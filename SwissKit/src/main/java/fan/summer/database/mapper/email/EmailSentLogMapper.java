package fan.summer.database.mapper.email;

import fan.summer.database.entity.email.EmailSentLogEntity;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface EmailSentLogMapper {
    int insert(EmailSentLogEntity log);
    int deleteById(Long id);
    int update(EmailSentLogEntity log);
    EmailSentLogEntity selectById(Long id);
    List<EmailSentLogEntity> selectAll();
    List<EmailSentLogEntity> selectBySuccess(@Param("isSuccess") boolean isSuccess);
    List<EmailSentLogEntity> selectByDateRange(@Param("startDate") Date startDate, @Param("endDate") Date endDate);
    int deleteAll();
    long count();
    long countBySuccess(@Param("isSuccess") boolean isSuccess);
}
