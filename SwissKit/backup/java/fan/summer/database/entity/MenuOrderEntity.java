package fan.summer.database.entity;

import lombok.Data;

/**
 * Entity representing a menu item's custom ordering.
 * Stores user-defined drag-and-drop order for sidebar menu items.
 *
 * @author summer
 */
@Data
public class MenuOrderEntity {
    /** Unique identifier */
    private Integer id;

    /** Fully qualified class name of the KitPage */
    private String pageClass;

    /** User-defined order value (lower = higher in menu) */
    private Integer menuOrder;
}