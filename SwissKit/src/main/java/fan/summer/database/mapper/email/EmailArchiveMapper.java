package fan.summer.database.mapper.email;

import fan.summer.database.entity.email.EmailArchiveEntity;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface EmailArchiveMapper {
    int insert(EmailArchiveEntity entity);

    EmailArchiveEntity selectByUid(@Param("accountEmail") String accountEmail,
                                    @Param("folder") String folder,
                                    @Param("messageUid") String messageUid);

    List<EmailArchiveEntity> selectByQuery(@Param("accountEmail") String accountEmail,
                                            @Param("fromAddress") String fromAddress,
                                            @Param("subject") String subject,
                                            @Param("startDate") Date startDate,
                                            @Param("endDate") Date endDate);
}
