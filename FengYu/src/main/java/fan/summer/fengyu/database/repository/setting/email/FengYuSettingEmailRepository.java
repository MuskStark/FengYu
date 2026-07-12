package fan.summer.fengyu.database.repository.setting.email;

import fan.summer.fengyu.database.entity.setting.email.FengYuSettingEmailEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FengYuSettingEmailRepository extends JpaRepository<FengYuSettingEmailEntity, Integer> {
    Optional<FengYuSettingEmailEntity> findFirstByUserIdOrderByIdDesc(Long userId);
}
