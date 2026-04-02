package plugin.swisskit.hpl.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import plugin.swisskit.hpl.dto.*;
import plugin.swisskit.hpl.util.ConfigLoader;
import plugin.swisskit.hpl.util.WebUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Course learning service - handles all course learning operations.
 *
 * @author summer
 * @version 1.00
 * @Date 2026/4/2
 */
public class CourseLearningService {

    private static final Logger log = LoggerFactory.getLogger(CourseLearningService.class);

    private final CourseQueryService queryService;

    private volatile Long currentLessonId;
    private volatile String currentLessonName;
    private volatile Float classHours;
    private volatile boolean skipSignal = false;
    private volatile Long skipLessonId;
    private volatile int lessonTypeCode;

    public CourseLearningService(CourseQueryService queryService) {
        this.queryService = queryService;
    }

    public Long getCurrentLessonId() {
        return currentLessonId;
    }

    public String getCurrentLessonName() {
        return currentLessonName;
    }

    public Float getClassHours() {
        return classHours;
    }

    public void setSkipSignal(boolean skipSignal) {
        this.skipSignal = skipSignal;
    }

    // -------------------------  Public API  -------------------------

    /**
     * Auto learning - automatically select course type based on remaining hours and complete learning
     *
     * @param lessonType Specified course type, pass null for auto detection
     * @param token      Mobile token
     * @param cookie     Login Cookie
     */
    public void autoLearning(String lessonType, String token, String cookie) {
        int page = 1;
        log.info("--------Start Learning--------");

        UserSearchResp personInfo = queryService.getPersonInfo(cookie, token);
        PeriodDataRU periodDataRU = personInfo.getData().getPeriodDataRU();
        logLearningStatus(periodDataRU);

        lessonType = resolveLessonType(lessonType, periodDataRU);
        log.info("Learning type: " + lessonType);

        while (queryAndLearnLessons(page, lessonType, token, cookie)) {
            if (isThreadInterrupted()) {
                log.info("Learning stopped by user");
                break;
            }
            page++;
        }
    }

    /**
     * Check if thread is interrupted (external cancellation)
     */
    private boolean isThreadInterrupted() {
        return Thread.currentThread().isInterrupted();
    }

    private void logLearningStatus(PeriodDataRU periodDataRU) {
        String status = String.format(
                "Learning Status: %nMajorSubject - Learned [%s/%s]h %nElectiveSubject - Learned [%s/%s]h",
                periodDataRU.getGroupLearningTotal(), periodDataRU.getGroupLearningGoal(),
                periodDataRU.getSelfLearningTotal(), periodDataRU.getSelfLearningGoal());
        log.info(status);
    }

    private String resolveLessonType(String lessonType, PeriodDataRU periodDataRU) {
        if (lessonType == null || lessonType.isEmpty()) {
            if (periodDataRU.getSelfLearningGoal() - periodDataRU.getSelfLearningTotal() > 0) {
                this.lessonTypeCode = LearningConstants.LESSON_TYPE_ELECTIVE;
                return "ElectiveSubject";
            } else if (periodDataRU.getGroupLearningGoal() - periodDataRU.getGroupLearningTotal() > 0) {
                this.lessonTypeCode = LearningConstants.LESSON_TYPE_MAJOR;
                return "MajorSubject";
            }
            return null;
        }

        if (isLessonTypePassed(lessonType, periodDataRU)) {
            throw new RuntimeException("Course category [" + lessonType + "] already passed");
        }
        return lessonType;
    }

    private boolean queryAndLearnLessons(int page, String lessonType, String token, String cookie) {
        if (isThreadInterrupted()) {
            return false;
        }

        log.info("Searching for courses, page {}", page);
        LessonSearchResp response = queryService.queryLessonList(page, ConfigLoader.lessonType(lessonType), token, cookie);

        if (response == null || response.getData() == null) {
            return false;
        }

        List<LessonInfo> lessons = response.getData().getList();
        log.info("Found {} courses", lessons.size());

        for (LessonInfo lesson : lessons) {
            if (isThreadInterrupted()) {
                return false;
            }

            if (isPassed(lesson.getIsPass())) {
                continue;
            }

            // Check if this lesson was skipped
            if (this.skipLessonId != null && this.skipLessonId.equals(lesson.getLessonId())) {
                log.info("Skipped lesson reached again, ID: {}, continuing", lesson.getLessonId());
                clearSkipState();
                continue;
            }

            if (!processLesson(lesson, token)) {
                // Lesson was skipped, clear skipSignal so next lesson continues normally
                this.skipSignal = false;
                continue;
            }

            if (isLessonTypePassed(lessonType, queryService.getPersonInfo(cookie, token).getData().getPeriodDataRU())) {
                log.info("Course category [{}] passed", lessonType);
                return false;
            }
        }
        return true;
    }

    private boolean processLesson(LessonInfo lesson, String token) {
        Long lessonId = lesson.getLessonId();
        log.info("Found unpassed course, ID: {}", lessonId);

        if (this.skipLessonId != null && this.skipLessonId.equals(lessonId)) {
            clearSkipState();
            log.info("Skipped lesson reached again, continuing");
            return false;
        }

        if (!enterLesson(lessonId, token)) {
            throw new RuntimeException("Failed to open course");
        }

        LessonDetailResp detail = queryService.getLessonInfo(lessonId, token);
        if (detail == null || detail.getData() == null) {
            return false;
        }

        this.currentLessonId = lessonId;
        this.currentLessonName = detail.getData().getLessonDetailVO().getName();
        this.classHours = detail.getData().getLessonDetailVO().getClasshour();
        log.info("Learning lesson - ID: {}, Name: {}", this.currentLessonId, this.currentLessonName);

        boolean success = processSubLessons(detail.getData().getUserlearncoursewareVOList(), lessonId, token);
        return success;
    }

    private boolean processSubLessons(List<UserLearnCourseWareVOList> subLessons, Long lessonId, String token) {
        if (subLessons == null) {
            return true;
        }

        log.info("Sub-course count: {}", subLessons.size());

        for (UserLearnCourseWareVOList subLesson : subLessons) {
            if (this.skipSignal) {
                this.skipLessonId = lessonId;
                return false;
            }

            Integer passed = subLesson.getPassed();
            if (passed == null) {
                subLesson.setPassed(LearningConstants.DEFAULT_PASSED);
                passed = LearningConstants.DEFAULT_PASSED;
            }

            if (passed != LearningConstants.PASS_CHECK || this.lessonTypeCode == LearningConstants.LESSON_TYPE_MAJOR) {
                log.info("Starting sub-course, ID: {}", subLesson.getCoursewareId());
                boolean result = upLoadLessonLearning(lessonId, subLesson.getCoursewareId(), token, this.lessonTypeCode);
                if (!result) {
                    this.skipLessonId = lessonId;
                    return false;
                }
            }
        }
        return true;
    }

    private void clearSkipState() {
        this.skipLessonId = null;
        this.skipSignal = false;
    }

    /**
     * Study specified course (single course)
     *
     * @param lessonId       Course ID
     * @param token          Mobile token
     * @param lessonTypeCode LearningConstants.LESSON_TYPE_ELECTIVE or LESSON_TYPE_MAJOR
     */
    public void studyLesson(String lessonId, String token, int lessonTypeCode) {
        LessonDetailResp lessonDetailResp = queryService.getLessonInfo(toLong(lessonId), token);
        log.info("Course details: " + lessonDetailResp);

        if (lessonDetailResp == null || lessonDetailResp.getData() == null) {
            return;
        }
        if (lessonDetailResp.getData().getPassed() != null
                && lessonDetailResp.getData().getPassed() == LearningConstants.PASS_CHECK) {
            throw new RuntimeException("Course already passed, no need to learn");
        }
        if (!enterLesson(toLong(lessonId), token)) {
            throw new RuntimeException("Failed to open course");
        }

        List<UserLearnCourseWareVOList> subLessons =
                lessonDetailResp.getData().getUserlearncoursewareVOList();
        log.info("Sub-course count: " + subLessons.size());

        for (UserLearnCourseWareVOList subLesson : subLessons) {
            if (subLesson.getPassed() == null) {
                subLesson.setPassed(LearningConstants.DEFAULT_PASSED);
            }
            if (subLesson.getPassed() != LearningConstants.PASS_CHECK) {
                log.info("Starting sub-course, course ID: " + subLesson.getCoursewareId());
                upLoadLessonLearning(toLong(lessonId), subLesson.getCoursewareId(), token, lessonTypeCode);
            }
        }
    }

    // -------------------------  Private Methods  -------------------------

    /**
     * Open (enter) a course
     * API at portalUrl, using portalJsonPost profile
     */
    private boolean enterLesson(Long lessonId, String token) {
        Map<String, List<String>> headers = toMultiValueMap(
                ConfigLoader.headers("portalJsonPost", Map.of("M0biletoken", token)));

        Map<String, Object> body = new HashMap<>();
        body.put("businessId", lessonId);
        body.put("businesstype", this.lessonTypeCode);
        body.put("m0biletoken", token);
        body.put("lessonId", lessonId);

        String uri = ConfigLoader.rawUrl("enterLesson") + "?_t=" + System.currentTimeMillis();
        EnterLessonResp resp = WebUtil.sendPostRequest(
                ConfigLoader.rawUrl("baseUrl"), uri, headers, body, EnterLessonResp.class);
        return resp != null && resp.getStatus() != null && resp.getStatus() == LearningConstants.HTTP_OK;
    }

    /**
     * Upload learning progress, loop until sub-course passed or retry threshold exceeded.
     * Supports breakpoint learning: resumes from the server's recorded exitplaytime.
     */
    private boolean upLoadLessonLearning(Long lessonId, Long coursewareId, String token, int lessonTypeCode) {
        Map<String, List<String>> headers = toMultiValueMap(
                ConfigLoader.headers("portalJsonPost", Map.of("M0biletoken", token)));

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        double exitPlaytime = getExitPlaytimeFromServer(lessonId, coursewareId, token);
        log.info("Breakpoint resume - initial exitplaytime: {}", exitPlaytime);

        boolean isLearning = true;
        boolean isFinished = false;
        int retryTimes = 0;
        float learnProgressHistory = 0f;

        while (isLearning) {
            // Check stop conditions at start of each iteration
            if (this.skipSignal) {
                this.skipLessonId = lessonId;
                log.info("Skip signal received, skipping lesson ID: {}", lessonId);
                return false;
            }
            if (isThreadInterrupted()) {
                log.info("Thread interrupted, stopping learning");
                return false;
            }

            double learnTime = LearningConstants.LEARN_TIME_MIN
                    + (LearningConstants.LEARN_TIME_MAX - LearningConstants.LEARN_TIME_MIN) * Math.random();
            log.info("Simulated learning time: {}", learnTime);
            exitPlaytime = exitPlaytime + learnTime - LearningConstants.EXIT_TIME_DEDUCTION * Math.random();

            learnTime = BigDecimal.valueOf(learnTime).setScale(3, RoundingMode.HALF_UP).doubleValue();
            exitPlaytime = BigDecimal.valueOf(exitPlaytime).setScale(6, RoundingMode.HALF_UP).doubleValue();

            Map<String, Object> body = new HashMap<>();
            body.put("businessId", lessonId);
            body.put("businesstype", lessonTypeCode);
            body.put("coursewareId", coursewareId);
            body.put("exitplaytime", exitPlaytime);
            body.put("finished", LearningConstants.DEFAULT_FINISHED);
            body.put("learncount", LearningConstants.DEFAULT_LEARN_COUNT);
            body.put("learndate", LocalDate.now().format(dtf));
            body.put("learntime", learnTime);
            body.put("lessonId", lessonId);
            List<Map<String, Object>> requestBody = new ArrayList<>();
            requestBody.add(body);

            try {
                Thread.sleep((long) (learnTime * 1000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Sleep interrupted, returning false");
                return false;
            }

            log.info("Uploading learning progress, exitplaytime: {}, learntime: {}", exitPlaytime, learnTime);
            String uri = ConfigLoader.rawUrl("uploadLearn") + "?_t=" + System.currentTimeMillis();
            WebUtil.sendPostRequest(
                    ConfigLoader.rawUrl("baseUrl"), uri, headers, requestBody, String.class);

            LessonDetailResp lessonDetailResp = queryService.getLessonInfo(lessonId, token);
            if (lessonDetailResp == null || lessonDetailResp.getData() == null) {
                continue;
            }
            for (UserLearnCourseWareVOList info :
                    lessonDetailResp.getData().getUserlearncoursewareVOList()) {
                if (info.getCoursewareId().equals(coursewareId)) {
                    if (info.getPassed() != null && info.getPassed() == LearningConstants.PASS_CHECK) {
                        isLearning = false;
                        isFinished = true;
                        log.info("Learning completed, sub-course ID: " + info.getCoursewareId());
                    } else {
                        log.info("Course: " + info.getCoursewareId()
                                + ", learning progress: " + info.getLearnProgress());
                        if (Objects.equals(info.getLearnProgress(), learnProgressHistory)) {
                            retryTimes++;
                            log.info("Course: " + info.getCoursewareId()
                                    + ", progress stalled, retrying: " + retryTimes + " times");
                        }
                        if (retryTimes >= LearningConstants.MAX_RETRY_STALL) {
                            log.info("Course: " + info.getCoursewareId()
                                    + ", progress stalled, skipping this course.");
                            isLearning = false;
                        }
                        learnProgressHistory = info.getLearnProgress() != null
                                ? info.getLearnProgress() : learnProgressHistory;
                    }
                }
            }
        }
        return isFinished;
    }

    /**
     * Get the recorded exitplaytime from server for a specific courseware.
     * Returns 0.0 if not found or on error.
     */
    private double getExitPlaytimeFromServer(Long lessonId, Long coursewareId, String token) {
        try {
            LessonDetailResp resp = queryService.getLessonInfo(lessonId, token);
            if (resp != null && resp.getData() != null) {
                for (UserLearnCourseWareVOList info : resp.getData().getUserlearncoursewareVOList()) {
                    if (coursewareId.equals(info.getCoursewareId())) {
                        return info.getExitplaytime() != null ? info.getExitplaytime() : 0.0;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get exitplaytime from server: {}", e.getMessage());
        }
        return 0.0;
    }

    /**
     * Check if specified course type has met the learning hour requirement
     */
    private boolean isLessonTypePassed(String lessonType, PeriodDataRU periodDataRU) {
        if ("ElectiveSubject".equals(lessonType)) {
            return (periodDataRU.getSelfLearningGoal() - periodDataRU.getSelfLearningTotal()) <= 0f;
        } else if ("MajorSubject".equals(lessonType)) {
            return (periodDataRU.getGroupLearningGoal() - periodDataRU.getGroupLearningTotal()) <= 0f;
        } else {
            throw new RuntimeException("Unsupported course type");
        }
    }

    /**
     * Convert Map&lt;String, String&gt; to Map&lt;String, List&lt;String&gt;&gt; required by WebUtil
     */
    private Map<String, List<String>> toMultiValueMap(Map<String, String> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        source.forEach((k, v) -> result.put(k, List.of(v)));
        return result;
    }

    /**
     * Convert String to Long safely
     */
    private Long toLong(String id) {
        return Long.valueOf(id);
    }

    /**
     * Check if a course is passed (null-safe)
     */
    private boolean isPassed(Integer isPass) {
        return isPass != null && isPass == LearningConstants.PASS_CHECK;
    }
}
