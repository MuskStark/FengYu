package plugin.swisskit.hpl.dto;

import lombok.Data;

import java.util.List;

/**
 * Lesson detail response data wrapper.
 * Maps to the "data" field in LessonDetailResp API response.
 *
 * @since 2026-03-25
 */
@Data
public class LessonDetailData {
    private Long lessonId;
    private LessonDetailVO lessonDetailVO;
    private List<UserLearnCourseWareVOList> userlearncoursewareVOList;
    private Integer userlearncoursewareVOExitIndex;
    private Float learnprogress;
    private Integer totaltime;
    private Integer passed;
    private Integer isfavo;
    private Long businessId;
    private Integer businesstype;
    private String businessname;
    private Object examArrangeVO;
    private Object userSurveyVO;
    private Integer isAutoContinuePlay;
}