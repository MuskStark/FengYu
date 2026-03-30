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
 * Auto learning plugin.swisskit.hpl.service
 *
 * @author summer
 * @version 1.00
 * @Date 2026/3/19
 */
public class HappyLearningService {

    private static final Logger log = LoggerFactory.getLogger(HappyLearningService.class);

    private volatile Long currentLessonId;
    private volatile String currentLessonName;
    private volatile Float classHours;

    public Long getCurrentLessonId() {
        return currentLessonId;
    }

    public String getCurrentLessonName() {
        return currentLessonName;
    }

    public Float getClassHours() {
        return classHours;
    }

    // -------------------------  Public API  -------------------------

    /**
     * Query personal learning info
     *
     * @param cookie Login Cookie
     * @param token  Mobile token
     */
    public UserSearchResp getPersonInfo(String cookie, String token) {
        Map<String, String> dynamics = new LinkedHashMap<>();
        dynamics.put("Cookie", cookie);
        dynamics.put("M0biletoken", token);
        Map<String, List<String>> headers = toMultiValueMap(
                ConfigLoader.headers("personInfoPost", dynamics));

        // Build query params string for URL
        StringBuilder queryBuilder = new StringBuilder();
        queryBuilder.append("openSchedule=1");
//        queryBuilder.append("&m0biletoken=").append(token);
//        queryBuilder.append("&_t=").append(System.currentTimeMillis());

        String uri = ConfigLoader.rawUrl("personInfo") + "?" + queryBuilder;

        return WebUtil.sendPostRequest(
                ConfigLoader.rawUrl("baseUrl"), uri, headers, null, UserSearchResp.class);
    }

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

        UserSearchResp personInfo = getPersonInfo(cookie, token);
        PeriodDataRU periodDataRU = personInfo.getData().getPeriodDataRU();

        String learnStatus = String.format(
                "Learning Status: %nMajorSubject - Learned Hours [%s] - Required Hours [%s]%nElectiveSubject - Learned Hours [%s] - Required Hours [%s]",
                periodDataRU.getGroupLearningTotal(), periodDataRU.getGroupLearningGoal(),
                periodDataRU.getSelfLearningTotal(), periodDataRU.getSelfLearningGoal());
        log.info(learnStatus);

        // Prioritize ElectiveSubject when no course type specified
        if (lessonType == null || lessonType.isEmpty()) {
            if (periodDataRU.getSelfLearningGoal() - periodDataRU.getSelfLearningTotal() > 0) {
                lessonType = "ElectiveSubject";
            } else if (periodDataRU.getGroupLearningGoal() - periodDataRU.getGroupLearningTotal() > 0) {
                lessonType = "MajorSubject";
            }
        } else {
            if (isLessonTypePassed(lessonType, periodDataRU)) {
                throw new RuntimeException("Course category [" + lessonType + "] already passed, no need to learn");
            }
        }

        log.info("Learning type: " + lessonType);

        float targetScore = 0f;
        if ("ElectiveSubject".equals(lessonType)) {
            targetScore = periodDataRU.getSelfLearningGoal() - periodDataRU.getSelfLearningTotal();
        } else if ("MajorSubject".equals(lessonType)) {
            targetScore = periodDataRU.getGroupLearningGoal() - periodDataRU.getGroupLearningTotal();
        }
        log.info("Learning target: " + targetScore);

        boolean startLearning = true;
        while (startLearning) {
            log.info("Searching for courses, page " + page);
            LessonSearchResp lessonSearchResp = queryLessonList(page, ConfigLoader.lessonType(lessonType), token, cookie);

            if (lessonSearchResp != null && lessonSearchResp.getData() != null) {
                List<LessonInfo> lessons = lessonSearchResp.getData().getList();
                log.info("Found " + lessons.size() + " courses");

                for (LessonInfo lessonInfo : lessons) {
                    if (lessonInfo.getIsPass() == null) {
                        lessonInfo.setIsPass(0);
                    }
                    if (lessonInfo.getIsPass() != 1.0f) {
                        log.info("Found unpassed course, course ID: " + lessonInfo.getLessonId());

                        if (!enterLesson(Long.valueOf(lessonInfo.getLessonId()), token)) {
                            throw new RuntimeException("Failed to open course");
                        }

                        LessonDetailResp lessonDetailResp = getLessonInfo(
                                Long.valueOf(lessonInfo.getLessonId()), token);
                        log.info("Course details: " + lessonDetailResp);

                        if (lessonDetailResp != null && lessonDetailResp.getData() != null) {
                            this.currentLessonId = Long.valueOf(lessonInfo.getLessonId());
                            this.currentLessonName = lessonDetailResp.getData().getLessonDetailVO().getName();
                            this.classHours = lessonDetailResp.getData().getLessonDetailVO().getClasshour();
                            log.info("Current lesson set - ID: {}, Name: {}", this.currentLessonId, this.currentLessonName);

                            List<UserLearnCourseWareVOList> subLessons =
                                    lessonDetailResp.getData().getUserlearncoursewareVOList();
                            log.info("Sub-course count: " + subLessons.size());

                            for (UserLearnCourseWareVOList subLesson : subLessons) {
                                if (subLesson.getPassed() == null) {
                                    subLesson.setPassed(0);
                                }
                                if (subLesson.getPassed() != 1) {
                                    log.info("Starting sub-course, course ID: " + subLesson.getCoursewareId());
                                    upLoadLessonLearning(
                                            Long.valueOf(lessonInfo.getLessonId()),
                                            subLesson.getCoursewareId(), token);
                                }
                            }
                        }

                        log.info("Checking learning progress");
                        UserSearchResp checkInfo = getPersonInfo(cookie, token);
                        if (isLessonTypePassed(lessonType, checkInfo.getData().getPeriodDataRU())) {
                            log.info("Course category [" + lessonType + "] passed");
                            startLearning = false;
                            break;
                        } else {
                            log.info("Course category [" + lessonType + "] not passed, continue learning");
                        }
                    }
                }
            }
            page++;
        }
    }

    /**
     * Study specified course (single course)
     *
     * @param lessonId Course ID
     * @param token    Mobile token
     */
    public void studyLesson(String lessonId, String token) {
        LessonDetailResp lessonDetailResp = getLessonInfo(Long.valueOf(lessonId), token);
        log.info("Course details: " + lessonDetailResp);

        if (lessonDetailResp == null || lessonDetailResp.getData() == null) {
            return;
        }
        if (lessonDetailResp.getData().getPassed() != null
                && lessonDetailResp.getData().getPassed() == 1) {
            throw new RuntimeException("Course already passed, no need to learn");
        }
        if (!enterLesson(Long.valueOf(lessonId), token)) {
            throw new RuntimeException("Failed to open course");
        }

        List<UserLearnCourseWareVOList> subLessons =
                lessonDetailResp.getData().getUserlearncoursewareVOList();
        log.info("Sub-course count: " + subLessons.size());

        for (UserLearnCourseWareVOList subLesson : subLessons) {
            if (subLesson.getPassed() == null) {
                subLesson.setPassed(0);
            }
            if (subLesson.getPassed() != 1) {
                log.info("Starting sub-course, course ID: " + subLesson.getCoursewareId());
                upLoadLessonLearning(Long.valueOf(lessonId), subLesson.getCoursewareId(), token);
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
        body.put("businesstype", 1);
        body.put("m0biletoken", token);
        body.put("lessonId", lessonId);

        String uri = ConfigLoader.rawUrl("enterLesson") + "?_t=" + System.currentTimeMillis();
        EnterLessonResp resp = WebUtil.sendPostRequest(
                ConfigLoader.rawUrl("baseUrl"), uri, headers, body, EnterLessonResp.class);
        return resp != null && resp.getStatus() != null && resp.getStatus() == 200;
    }

    /**
     * Upload learning progress, loop until sub-course passed or retry threshold exceeded.
     * Supports breakpoint learning: resumes from the server's recorded exitplaytime.
     */
    private boolean upLoadLessonLearning(Long lessonId, Long coursewareId, String token) {
        Map<String, List<String>> headers = toMultiValueMap(
                ConfigLoader.headers("portalJsonPost", Map.of("M0biletoken", token)));

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        // Get initial exitplaytime from server for breakpoint resume
        double exitPlaytime = getExitPlaytimeFromServer(lessonId, coursewareId, token);
        log.info("Breakpoint resume - initial exitplaytime: {}", exitPlaytime);

        boolean isLearning = true;
        boolean isFinished = false;
        final double maxSecond = 61.0;
        final double minSecond = 60.0;
        int retryTimes = 0;
        float learnProgressHistory = 0f;

        while (isLearning) {
            double learnTime = minSecond + (maxSecond - minSecond) * Math.random();
            log.info("Simulated learning time: {}", learnTime);
            exitPlaytime = exitPlaytime + learnTime - 0.99999 * Math.random();

            learnTime = BigDecimal.valueOf(learnTime).setScale(3, RoundingMode.HALF_UP).doubleValue();
            exitPlaytime = BigDecimal.valueOf(exitPlaytime).setScale(6, RoundingMode.HALF_UP).doubleValue();

            // Build request body as array with single entry per specification
            Map<String, Object> body = new HashMap<>();
            body.put("businessId", lessonId);
            body.put("businesstype", 1);
            body.put("coursewareId", coursewareId);
            body.put("exitplaytime", exitPlaytime);
            body.put("finished", 0);
            body.put("learncount", 0);
            body.put("learndate", LocalDate.now().format(dtf));
            body.put("learntime", learnTime);
            body.put("lessonId", lessonId);
            List<Map<String, Object>> requestBody = new ArrayList<>();
            requestBody.add(body);

            try {
                Thread.sleep((long) (learnTime * 1000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }

            log.info("Uploading learning progress, exitplaytime: {}, learntime: {}", exitPlaytime, learnTime);
            String uri = ConfigLoader.rawUrl("uploadLearn") + "?_t=" + System.currentTimeMillis();
            WebUtil.sendPostRequest(
                    ConfigLoader.rawUrl("baseUrl"), uri, headers, requestBody, String.class);

            // Check if learning completed
            LessonDetailResp lessonDetailResp = getLessonInfo(lessonId, token);
            if (lessonDetailResp == null || lessonDetailResp.getData() == null) {
                continue;
            }
            for (UserLearnCourseWareVOList info :
                    lessonDetailResp.getData().getUserlearncoursewareVOList()) {
                if (info.getCoursewareId().equals(coursewareId)) {
                    if (info.getPassed() != null && info.getPassed() == 1) {
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
                        if (retryTimes >= 2) {
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
            LessonDetailResp resp = getLessonInfo(lessonId, token);
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
     * Query course details (portalGet)
     */
    private LessonDetailResp getLessonInfo(Long lessonId, String token) {
        Map<String, String> headers =
                ConfigLoader.headers("portalGet", Map.of("M0biletoken", token));

        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("_t", List.of(String.valueOf(System.currentTimeMillis())));
        params.put("m0biletoken", List.of(token));
        params.put("lessonId", List.of(String.valueOf(lessonId)));
        params.put("businessId", List.of(String.valueOf(lessonId)));
        params.put("businesstype", List.of("1"));

        return WebUtil.sendGetRequest(
                ConfigLoader.rawUrl("baseUrl"),
                ConfigLoader.rawUrl("lessonInfo"),
                headers, params, LessonDetailResp.class);
    }

    /**
     * Query course list (paginated, mainSiteGet)
     */
    private LessonSearchResp queryLessonList(Integer page, String lessonType,
                                             String token, String cookie) {
        Map<String, String> dynamics = new LinkedHashMap<>();
        dynamics.put("M0biletoken", token);
        dynamics.put("Cookie", cookie);
        Map<String, String> headers = ConfigLoader.headers("mainSiteGet", dynamics);

        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("_t", List.of(String.valueOf(System.currentTimeMillis())));
        params.put("pageNumber", List.of(String.valueOf(page)));
        params.put("pageSize", List.of("16"));
        params.put("classHourTypes", List.of(lessonType));

        return WebUtil.sendGetRequest(
                ConfigLoader.rawUrl("baseUrl"),
                ConfigLoader.rawUrl("lessonList"),
                headers, params, LessonSearchResp.class);
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
     * Convert Map<String, String> to Map<String, List<String>> required by WebUtil
     */
    private Map<String, List<String>> toMultiValueMap(Map<String, String> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        source.forEach((k, v) -> result.put(k, List.of(v)));
        return result;
    }
}
