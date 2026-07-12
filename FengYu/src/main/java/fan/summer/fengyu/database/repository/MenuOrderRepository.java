package fan.summer.fengyu.database.repository;

import fan.summer.fengyu.database.entity.MenuOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MenuOrderRepository extends JpaRepository<MenuOrderEntity, Integer> {
    List<MenuOrderEntity> findAllByUserIdOrderByMenuOrderAsc(Long userId);
}
