package plugin.swisskit.hpl.dto;


import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

/**
 * User learning courseware progress information.
 * Maps to items in "userlearncoursewareVOList" array in lesson detail API response.
 *
 * @since 2026-03-19
 */
@Data
public class UserLearnCourseWareVOList {
    private CoursewareVO coursewareVO;

    private Long lessonId;
    private Long coursewareId;

    @JSONField(name = "learnprogress")
    private Float learnProgress;

    private Float totaltime;
    private Integer passed;
    private Integer status;
    private Integer isallowlearn;

    @JSONField(name = "exitplaytime")
    private Float exitplaytime;

    private Integer isexit;
}