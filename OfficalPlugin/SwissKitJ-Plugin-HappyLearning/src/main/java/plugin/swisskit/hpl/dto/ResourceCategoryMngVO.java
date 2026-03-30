package plugin.swisskit.hpl.dto;

import lombok.Data;

/**
 * Resource category info from lesson detail API.
 *
 * @since 2026-03-25
 */
@Data
public class ResourceCategoryMngVO {
    private Long rescategoryId;
    private String name;
    private String fullname;
    private Integer ordernum;
}
