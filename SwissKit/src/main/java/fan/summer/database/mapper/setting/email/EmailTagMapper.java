package fan.summer.database.mapper.setting.email;

import fan.summer.database.entity.setting.email.EmailTagEntity;

import java.util.List;

public interface EmailTagMapper {
    void insert(EmailTagEntity emailTagEntity);
    void update(EmailTagEntity emailTagEntity);
    List<EmailTagEntity> selectAll();
}
