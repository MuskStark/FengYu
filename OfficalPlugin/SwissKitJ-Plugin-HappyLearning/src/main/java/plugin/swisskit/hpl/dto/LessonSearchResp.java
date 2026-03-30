package plugin.swisskit.hpl.dto;

import lombok.Data;

/**
 * Lesson search API response.
 *
 * @since 2026-03-19
 */
@Data
public class LessonSearchResp {
    private Integer status;
    private String code;
    private String msg;
    private LessonSearchData data;
}
