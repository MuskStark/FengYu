package plugin.swisskit.hpl.dto;


import lombok.Data;

/**
 * Lesson detail wrapper referencing LessonDetailVO.
 * Kept for backward compatibility with code that accesses lessonDetailVO fields.
 *
 * @since 2026-03-19
 */
@Data
public class LessonDetail {
    private LessonDetailVO lessonDetailVO;
}