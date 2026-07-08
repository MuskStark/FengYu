package fan.summer.zhiflow.database.repository.setting.email;

import fan.summer.zhiflow.database.entity.setting.email.ZhiFlowSettingEmailEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ZhiFlowSettingEmailRepository extends JpaRepository<ZhiFlowSettingEmailEntity, Integer> {
    Optional<ZhiFlowSettingEmailEntity> findFirstByUserIdOrderByIdDesc(Long userId);
}
