package plugin.swisskit.hpl.dto;


import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

/**
 * Lesson information containing course details and progress.
 *
 * @since 2026-03-19
 */
@Data
public class LessonInfo {
    private Long lessonId;
    @JSONField(name = "classhour")
    private Integer classHour;
    @JSONField(name = "isonline")
    private Integer isOnline;
    @JSONField(name = "ispass")
    private Integer isPass;
}
