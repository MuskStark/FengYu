package fan.summer.fengyu.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

/**
 * Entity representing the display order of menu items in the sidebar.
 * User-scoped — each user has their own menu ordering.
 *
 * @since 3.0.0
 */
@Entity
@Table(name = "menu_order",
        uniqueConstraints = @UniqueConstraint(name = "uk_menu_order_user_page",
                columnNames = {"user_id", "page_class"}))
@Data
public class MenuOrderEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "page_class", nullable = false)
    private String pageClass;

    @Column(name = "menu_order", nullable = false)
    private Integer menuOrder;

    @Column(name = "user_id", nullable = false)
    private Long userId = 1L;
}
