package plugin.swisskit.hpl.service;

import plugin.swisskit.hpl.dto.UserSearchResp;

/**
 * Happy learning facade service - delegates to CourseQueryService and CourseLearningService.
 *
 * @author summer
 * @version 1.00
 * @Date 2026/3/19
 */
public class HappyLearningService {

    private final CourseQueryService queryService;
    private final CourseLearningService learningService;

    public HappyLearningService() {
        this.queryService = new CourseQueryService();
        this.learningService = new CourseLearningService(queryService);
    }

    public Long getCurrentLessonId() {
        return learningService.getCurrentLessonId();
    }

    public String getCurrentLessonName() {
        return learningService.getCurrentLessonName();
    }

    public Float getClassHours() {
        return learningService.getClassHours();
    }

    /**
     * Query personal learning info
     *
     * @param cookie Login Cookie
     * @param token  Mobile token
     */
    public UserSearchResp getPersonInfo(String cookie, String token) {
        return queryService.getPersonInfo(cookie, token);
    }

    /**
     * Auto learning - automatically select course type based on remaining hours and complete learning
     *
     * @param lessonType Specified course type, pass null for auto detection
     * @param token      Mobile token
     * @param cookie     Login Cookie
     */
    public void autoLearning(String lessonType, String token, String cookie) {
        learningService.autoLearning(lessonType, token, cookie);
    }

    /**
     * Study specified course (single course)
     *
     * @param lessonId       Course ID
     * @param token           Mobile token
     * @param lessonTypeCode  LearningConstants.LESSON_TYPE_ELECTIVE or LESSON_TYPE_MAJOR
     */
    public void studyLesson(String lessonId, String token, int lessonTypeCode) {
        learningService.studyLesson(lessonId, token, lessonTypeCode);
    }

    public void setSkipSignal(boolean skipSignal) {
        learningService.setSkipSignal(skipSignal);
    }
}
