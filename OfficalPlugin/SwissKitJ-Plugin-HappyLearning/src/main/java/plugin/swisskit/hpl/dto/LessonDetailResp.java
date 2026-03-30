package plugin.swisskit.hpl.dto;

import lombok.Data;

/**
 * Lesson detail API response wrapper.
 *
 * @since 2026-03-19
 */
@Data
public class LessonDetailResp {
    private Integer status;
    private String code;
    private String msg;
    private LessonDetailData data;
}
