package plugin.swisskit.hpl.dto;

import lombok.Data;

import java.util.List;

/**
 * Lesson search data with pagination info.
 *
 * @since 2026-03-19
 */
@Data
public class LessonSearchData {
    private Integer pageNumber;
    private Integer pageSize;
    private Integer totalCount;
    private Boolean listNullOrEmpty;
    private List<LessonInfo> list;
}
