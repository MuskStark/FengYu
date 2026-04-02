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
 * <p>
 * This service manages the end-to-end lifecycle of online course learning:
 * <ul>
 *   <li>Auto-detecting which course type (Elective/Major) needs learning based on remaining hours</li>
 *   <li>Querying available courses page by page and processing each lesson</li>
 *   <li>Entering lessons and uploading learning progress to the server</li>
 *   <li>Supporting breakpoint resume: resuming from the last recorded exitplaytime</li>
 *   <li>Skip functionality: allowing users to skip the current lesson and continue</li>
 * </ul>
 *
 * @author summer
 * @version 1.00
 * @Date 2026/4/2
 */
public class CourseLearningService {

    private static final Logger log = LoggerFactory.getLogger(CourseLearningService.class);

    /**
     * Query service for fetching course, person, and lesson information from the server.
     */
    private final CourseQueryService queryService;

    // -------------------------  Current Learning State  -------------------------
    // These fields track the currently active lesson and are updated as learning progresses.
    // They are volatile to ensure visibility across threads (learning happens in a separate thread
    // while UI polls these values via SwingWorker).

    /** ID of the lesson currently being learned or most recently completed */
    private volatile Long currentLessonId;

    /** Name of the current lesson (displayed in UI) */
    private volatile String currentLessonName;

    /** Class hours (duration) of the current lesson */
    private volatile Float classHours;

    // -------------------------  Skip Mechanism  -------------------------
    // Allows an external thread (e.g., UI button click) to request skipping the current lesson.
    // When skipSignal is set, the learning loop exits gracefully and records skipLessonId
    // to avoid re-entering the same lesson on the next iteration.

    /** Flag set by external thread to request skip of current lesson */
    private volatile boolean skipSignal = false;

    /** ID of the lesson that was skipped (to prevent re-entry when encountered again) */
    private volatile Long skipLessonId;

    /** Current lesson type code for API calls: LESSON_TYPE_ELECTIVE or LESSON_TYPE_MAJOR */
    private volatile int lessonTypeCode;

    /**
     * Constructs a new CourseLearningService.
     *
     * @param queryService Service for querying course and user information
     */
    public CourseLearningService(CourseQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * Returns the ID of the currently active lesson.
     * Called by SwingWorker to poll current state for UI display.
     *
     * @return current lesson ID, or null if no lesson is active
     */
    public Long getCurrentLessonId() {
        return currentLessonId;
    }

    /**
     * Returns the name of the currently active lesson.
     *
     * @return current lesson name, or null if no lesson is active
     */
    public String getCurrentLessonName() {
        return currentLessonName;
    }

    /**
     * Returns the class hours (duration) of the currently active lesson.
     *
     * @return class hours, or null if no lesson is active
     */
    public Float getClassHours() {
        return classHours;
    }

    /**
     * Sets the skip signal to request cancellation of the current lesson.
     * When set to true by an external thread, the current lesson upload loop
     * will exit gracefully at its next iteration boundary.
     *
     * @param skipSignal true to request skip, false to clear
     */
    public void setSkipSignal(boolean skipSignal) {
        this.skipSignal = skipSignal;
    }

    // -------------------------  Public API  -------------------------

    /**
     * Auto learning - automatically selects course type based on remaining hours
     * and continuously learns until the requirement is met or interrupted.
     * <p>
     * Auto-detection logic (when lessonType is null):
     * <ul>
     *   <li>If elective hours remaining > 0, learns ElectiveSubject first</li>
     *   <li>Otherwise, if major hours remaining > 0, learns MajorSubject</li>
     *   <li>If both requirements are already satisfied, returns immediately</li>
     * </ul>
     *
     * @param lessonType Specified course type (null for auto detection).
     *                   Accepted values: "ElectiveSubject", "MajorSubject", or null
     * @param token      Mobile API token (extracted from login cookie)
     * @param cookie     Login Cookie containing session info
     */
    public void autoLearning(String lessonType, String token, String cookie) {
        int page = 1;
        log.info("========== Start Auto Learning ==========");

        // Fetch current learning status from server to determine what needs learning
        UserSearchResp personInfo = queryService.getPersonInfo(cookie, token);
        PeriodDataRU periodDataRU = personInfo.getData().getPeriodDataRU();
        logLearningStatus(periodDataRU);

        // Resolve which lesson type to learn (auto-detect if not specified)
        lessonType = resolveLessonType(lessonType, periodDataRU);
        if (lessonType == null) {
            log.info("No lesson type needs learning - both requirements already satisfied");
            return;
        }
        log.info("Resolved lesson type: {}", lessonType);

        // Paginate through available courses, learning each until done or interrupted
        while (queryAndLearnLessons(page, lessonType, token, cookie)) {
            if (isThreadInterrupted()) {
                log.info("Learning interrupted by user");
                break;
            }
            page++;
        }

        log.info("========== Auto Learning Finished ==========");
    }

    /**
     * Checks if the current thread has been interrupted by an external cancellation.
     * Used for graceful shutdown when the user cancels learning.
     *
     * @return true if thread is interrupted, false otherwise
     */
    private boolean isThreadInterrupted() {
        return Thread.currentThread().isInterrupted();
    }

    /**
     * Logs the current learning status for both course types.
     * Shows learned hours vs. goal hours for each type.
     *
     * @param periodDataRU Period data containing learning progress
     */
    private void logLearningStatus(PeriodDataRU periodDataRU) {
        String status = String.format(
                "Learning Status:%n  MajorSubject   - Learned: %s/%s h%n  ElectiveSubject - Learned: %s/%s h",
                periodDataRU.getGroupLearningTotal(), periodDataRU.getGroupLearningGoal(),
                periodDataRU.getSelfLearningTotal(), periodDataRU.getSelfLearningGoal());
        log.info(status);
    }

    /**
     * Resolves which lesson type to learn based on remaining hours.
     * <ul>
     *   <li>If lessonType is explicitly specified and not passed, returns it</li>
     *   <li>If null, auto-detects: prefers Elective if needed, then Major</li>
     *   <li>If already passed, throws RuntimeException</li>
     * </ul>
     *
     * @param lessonType   Explicit type, or null for auto-detect
     * @param periodDataRU Current learning progress data
     * @return Resolved lesson type, or null if no learning needed
     * @throws RuntimeException if specified type is already passed
     */
    private String resolveLessonType(String lessonType, PeriodDataRU periodDataRU) {
        // Auto-detect if no specific type requested
        if (lessonType == null || lessonType.isEmpty()) {
            float electiveRemaining = periodDataRU.getSelfLearningGoal() - periodDataRU.getSelfLearningTotal();
            if (electiveRemaining > 0) {
                this.lessonTypeCode = LearningConstants.LESSON_TYPE_ELECTIVE;
                log.info("Auto-selected ElectiveSubject, remaining: {}h", electiveRemaining);
                return "ElectiveSubject";
            }

            float majorRemaining = periodDataRU.getGroupLearningGoal() - periodDataRU.getGroupLearningTotal();
            if (majorRemaining > 0) {
                this.lessonTypeCode = LearningConstants.LESSON_TYPE_MAJOR;
                log.info("Auto-selected MajorSubject, remaining: {}h", majorRemaining);
                return "MajorSubject";
            }

            return null;
        }

        // Validate specified type is not already passed
        if (isLessonTypePassed(lessonType, periodDataRU)) {
            throw new RuntimeException("Course category [" + lessonType + "] already passed");
        }

        this.lessonTypeCode = "ElectiveSubject".equals(lessonType)
                ? LearningConstants.LESSON_TYPE_ELECTIVE
                : LearningConstants.LESSON_TYPE_MAJOR;

        return lessonType;
    }

    /**
     * Queries a page of lessons and processes each one until completion or interruption.
     * <p>
     * For each lesson:
     * <ul>
     *   <li>Skips already-passed lessons</li>
     *   <li>Skips the previously skipped lesson (to avoid re-entry)</li>
     *   <li>Processes the lesson (enter, upload progress)</li>
     *   <li>Checks if the lesson type requirement is satisfied after each lesson</li>
     * </ul>
     *
     * @param page       Page number (1-indexed)
     * @param lessonType Type of lessons to query
     * @param token      Mobile API token
     * @param cookie     Login cookie
     * @return true if more pages available, false if done
     */
    private boolean queryAndLearnLessons(int page, String lessonType, String token, String cookie) {
        if (isThreadInterrupted()) {
            return false;
        }

        log.info("Querying lesson list, page {}", page);
        LessonSearchResp response = queryService.queryLessonList(page, ConfigLoader.lessonType(lessonType), token, cookie);

        if (response == null || response.getData() == null) {
            log.warn("Empty response for page {}, stopping", page);
            return false;
        }

        List<LessonInfo> lessons = response.getData().getList();
        log.info("Found {} lessons on page {}", lessons.size(), page);

        if (lessons.isEmpty()) {
            log.info("No more lessons on page {}, finished", page);
            return false;
        }

        for (LessonInfo lesson : lessons) {
            if (isThreadInterrupted()) {
                return false;
            }

            // Skip already-passed lessons
            if (isPassed(lesson.getIsPass())) {
                log.debug("Lesson {} already passed, skipping", lesson.getLessonId());
                continue;
            }

            // If we previously skipped this lesson, skip it again to avoid re-entry
            if (this.skipLessonId != null && this.skipLessonId.equals(lesson.getLessonId())) {
                log.info("Previously skipped lesson encountered again, ID: {}, clearing skip state",
                        lesson.getLessonId());
                clearSkipState();
                continue;
            }

            // Process this lesson; returns false if skipped
            boolean processed = processLesson(lesson, token);
            if (!processed) {
                // Lesson was skipped, reset signal so next lesson processes normally
                this.skipSignal = false;
                continue;
            }

            // Check if requirement is satisfied after this lesson
            PeriodDataRU periodDataRU = queryService.getPersonInfo(cookie, token).getData().getPeriodDataRU();
            if (isLessonTypePassed(lessonType, periodDataRU)) {
                log.info("Course category [{}] requirement met, stopping", lessonType);
                return false;
            }
        }

        return true;
    }

    /**
     * Processes a single lesson: enters it, extracts metadata, and uploads progress.
     * <p>
     * Steps:
     * <ol>
     *   <li>Enter the lesson (API call to mark lesson as started)</li>
     *   <li>Fetch lesson details (name, class hours, sub-lessons)</li>
     *   <li>Update currentLessonId, currentLessonName, classHours for UI polling</li>
     *   <li>Process all sub-lessons (coursewares)</li>
     * </ol>
     *
     * @param lesson Lesson info from search results
     * @param token  Mobile API token
     * @return true if completed normally, false if skipped
     * @throws RuntimeException if unable to enter lesson
     */
    private boolean processLesson(LessonInfo lesson, String token) {
        Long lessonId = lesson.getLessonId();
        log.info("Processing lesson, ID: {}", lessonId);

        // Re-check skip state in case it was set by another thread
        if (this.skipLessonId != null && this.skipLessonId.equals(lessonId)) {
            clearSkipState();
            log.info("Skip state active for lesson {}, skipping", lessonId);
            return false;
        }

        // Step 1: Enter (open) the lesson on the server
        if (!enterLesson(lessonId, token)) {
            throw new RuntimeException("Failed to enter lesson ID: " + lessonId);
        }

        // Step 2: Fetch lesson details including sub-lessons (coursewares)
        LessonDetailResp detail = queryService.getLessonInfo(lessonId, token);
        if (detail == null || detail.getData() == null) {
            log.warn("Could not fetch details for lesson ID: {}, skipping", lessonId);
            return false;
        }

        // Step 3: Update current lesson state for UI polling
        this.currentLessonId = lessonId;
        this.currentLessonName = detail.getData().getLessonDetailVO().getName();
        this.classHours = detail.getData().getLessonDetailVO().getClasshour();
        log.info("Current lesson updated - ID: {}, Name: {}, ClassHours: {}",
                this.currentLessonId, this.currentLessonName, this.classHours);

        // Step 4: Process all sub-lessons (coursewares)
        return processSubLessons(detail.getData().getUserlearncoursewareVOList(), lessonId, token);
    }

    /**
     * Processes all sub-lessons (coursewares) for a given lesson.
     * <p>
     * For each courseware:
     * <ul>
     *   <li>If skipSignal is set, marks lesson as skipped and returns false</li>
     *   <li>If not passed (or Major type regardless of pass status), uploads learning progress</li>
     *   <li>If upload fails/skipped, marks lesson as skipped</li>
     * </ul>
     *
     * @param subLessons List of coursewares to process
     * @param lessonId  Parent lesson ID
     * @param token     Mobile API token
     * @return true if all processed, false if lesson was skipped
     */
    private boolean processSubLessons(List<UserLearnCourseWareVOList> subLessons, Long lessonId, String token) {
        if (subLessons == null || subLessons.isEmpty()) {
            log.info("No sub-lessons found for lesson ID: {}", lessonId);
            return true;
        }

        log.info("Processing {} sub-lessons for lesson ID: {}", subLessons.size(), lessonId);

        for (UserLearnCourseWareVOList subLesson : subLessons) {
            // Check if skip was requested by external thread
            if (this.skipSignal) {
                this.skipLessonId = lessonId;
                log.info("Skip signal received, marking lesson {} as skipped", lessonId);
                return false;
            }

            // Normalize null passed status to default
            Integer passed = subLesson.getPassed();
            if (passed == null) {
                subLesson.setPassed(LearningConstants.DEFAULT_PASSED);
                passed = LearningConstants.DEFAULT_PASSED;
            }

            // For Major type, always re-learn regardless of pass status
            // For Elective type, only learn if not yet passed
            boolean needsLearning = (passed != LearningConstants.PASS_CHECK)
                    || (this.lessonTypeCode == LearningConstants.LESSON_TYPE_MAJOR);

            if (needsLearning) {
                Long coursewareId = subLesson.getCoursewareId();
                // Use totalTime from server for breakpoint resume
                float totalTime = subLesson.getTotaltime() != null ? subLesson.getTotaltime() : 0f;
                log.info("Uploading progress for courseware ID: {}, totalTime: {}s", coursewareId, totalTime);

                boolean result = upLoadLessonLearning(
                        lessonId, coursewareId, token, this.lessonTypeCode, totalTime);

                if (!result) {
                    this.skipLessonId = lessonId;
                    log.info("Courseware {} failed/skipped, marking lesson {} as skipped",
                            coursewareId, lessonId);
                    return false;
                }
            } else {
                log.debug("Courseware {} already passed, skipping", subLesson.getCoursewareId());
            }
        }

        return true;
    }

    /**
     * Clears the skip state after a skipped lesson is re-encountered
     * or when a new lesson starts processing.
     */
    private void clearSkipState() {
        this.skipLessonId = null;
        this.skipSignal = false;
    }

    /**
     * Study a specific single lesson (used for manual lesson selection).
     * Unlike autoLearning which processes all lessons, this learns only
     * the specified lesson and its coursewares.
     *
     * @param lessonId       Lesson ID to study
     * @param token          Mobile API token
     * @param lessonTypeCode LESSON_TYPE_ELECTIVE or LESSON_TYPE_MAJOR
     */
    public void studyLesson(String lessonId, String token, int lessonTypeCode) {
        log.info("========== Study Single Lesson: {} ==========", lessonId);

        LessonDetailResp lessonDetailResp = queryService.getLessonInfo(toLong(lessonId), token);
        if (lessonDetailResp == null || lessonDetailResp.getData() == null) {
            log.error("Failed to fetch lesson details for ID: {}", lessonId);
            return;
        }

        // Check if already passed
        if (lessonDetailResp.getData().getPassed() != null
                && lessonDetailResp.getData().getPassed() == LearningConstants.PASS_CHECK) {
            throw new RuntimeException("Lesson already passed, no need to learn: " + lessonId);
        }

        // Enter the lesson
        if (!enterLesson(toLong(lessonId), token)) {
            throw new RuntimeException("Failed to enter lesson: " + lessonId);
        }

        // Process coursewares
        List<UserLearnCourseWareVOList> subLessons =
                lessonDetailResp.getData().getUserlearncoursewareVOList();
        log.info("Lesson {} has {} coursewares", lessonId, subLessons.size());

        for (UserLearnCourseWareVOList subLesson : subLessons) {
            if (subLesson.getPassed() == null) {
                subLesson.setPassed(LearningConstants.DEFAULT_PASSED);
            }

            boolean needsFullLearn = subLesson.getPassed() != LearningConstants.PASS_CHECK;
            float totalTime = subLesson.getTotaltime() != null ? subLesson.getTotaltime() : 0f;

            if (needsFullLearn) {
                log.info("Learning courseware (full): {}", subLesson.getCoursewareId());
                upLoadLessonLearning(toLong(lessonId), subLesson.getCoursewareId(),
                        token, lessonTypeCode, totalTime);
            }
        }

        log.info("========== Study Single Lesson Finished ==========");
    }

    // -------------------------  Private Methods  -------------------------

    /**
     * Enters (opens) a lesson on the server.
     * This must be called before uploading learning progress.
     * API: POST {baseUrl}/enterLesson, profile: portalJsonPost
     *
     * @param lessonId Lesson ID to enter
     * @param token    Mobile API token
     * @return true if enter was successful (HTTP 200), false otherwise
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
        log.debug("Entering lesson {}, URI: {}", lessonId, uri);

        EnterLessonResp resp = WebUtil.sendPostRequest(
                ConfigLoader.rawUrl("baseUrl"), uri, headers, body, EnterLessonResp.class);

        boolean success = (resp != null && resp.getStatus() != null
                && resp.getStatus() == LearningConstants.HTTP_OK);
        log.debug("Enter lesson result: success={}, status={}", success,
                resp != null ? resp.getStatus() : "null");
        return success;
    }

    /**
     * Uploads learning progress for a courseware in a loop until passed or retry limit.
     * <p>
     * Implements breakpoint resume: fetches current exitplaytime from server,
     * simulates learning by sleeping, uploads progress, and repeats until passed.
     * <p>
     * Learning time calculation:
     * <ul>
     *   <li>Normally: random duration between LEARN_TIME_MIN and LEARN_TIME_MAX</li>
     *   <li>Near end (finalExitPlaytime > totalTime): capped at remaining time + up to 60s random</li>
     * </ul>
     *
     * @param lessonId       Parent lesson ID
     * @param coursewareId   Courseware ID to learn
     * @param token          Mobile API token
     * @param lessonTypeCode LESSON_TYPE_ELECTIVE or LESSON_TYPE_MAJOR
     * @param totalTime      Total courseware duration in seconds (from server)
     * @return true if passed, false if skipped or interrupted
     */
    private boolean upLoadLessonLearning(Long lessonId, Long coursewareId, String token,
                                         int lessonTypeCode, float totalTime) {
        Map<String, List<String>> headers = toMultiValueMap(
                ConfigLoader.headers("portalJsonPost", Map.of("M0biletoken", token)));

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        // Get server-side exitplaytime for breakpoint resume
        double exitPlaytime = getExitPlaytimeFromServer(lessonId, coursewareId, token);
        log.info("Breakpoint resume - courseware: {}, initial exitplaytime: {}s, totalTime: {}s",
                coursewareId, exitPlaytime, totalTime);

        boolean isLearning = true;
        boolean isFinished = false;
        int retryTimes = 0;
        float learnProgressHistory = 0f;

        while (isLearning) {
            // Check external stop signals at start of each iteration
            if (this.skipSignal) {
                this.skipLessonId = lessonId;
                log.info("Skip signal received, abandoning lesson ID: {}", lessonId);
                return false;
            }
            if (isThreadInterrupted()) {
                log.info("Thread interrupted, stopping for courseware {}", coursewareId);
                return false;
            }

            // Calculate learning time for this iteration
            // Random duration between MIN and MAX to simulate natural learning behavior
            double learnTime = LearningConstants.LEARN_TIME_MIN
                    + (LearningConstants.LEARN_TIME_MAX - LearningConstants.LEARN_TIME_MIN) * Math.random();
            log.debug("Calculated learnTime: {}s (random in range [{}, {}])",
                    learnTime, LearningConstants.LEARN_TIME_MIN, LearningConstants.LEARN_TIME_MAX);

            double finalExitPlaytime = exitPlaytime + learnTime;

            // If approaching end of courseware, cap the exitplaytime and add random buffer
            // This simulates finishing the courseware with some variability
            if (finalExitPlaytime > totalTime && totalTime > 0) {
                learnTime = totalTime - exitPlaytime;
                if (learnTime < 0) learnTime = 0;
                // Add up to 60 seconds random to simulate natural finishing variance
                finalExitPlaytime = exitPlaytime + learnTime + 60 * Math.random();
                log.debug("Near end of courseware, capped learnTime: {}s, finalExitPlaytime: {}s",
                        learnTime, finalExitPlaytime);
            }

            // Round to appropriate precision for API
            learnTime = BigDecimal.valueOf(learnTime).setScale(3, RoundingMode.HALF_UP).doubleValue();
            exitPlaytime = BigDecimal.valueOf(exitPlaytime).setScale(6, RoundingMode.HALF_UP).doubleValue();

            // Build learning record for API
            Map<String, Object> body = new HashMap<>();
            body.put("businessId", lessonId);
            body.put("businesstype", lessonTypeCode);
            body.put("coursewareId", coursewareId);
            body.put("exitplaytime", finalExitPlaytime);
            body.put("finished", LearningConstants.DEFAULT_FINISHED);
            body.put("learncount", LearningConstants.DEFAULT_LEARN_COUNT);
            body.put("learndate", LocalDate.now().format(dtf));
            body.put("learntime", learnTime);
            body.put("lessonId", lessonId);

            List<Map<String, Object>> requestBody = new ArrayList<>();
            requestBody.add(body);

            // Simulate learning by sleeping (server tracks actual elapsed time)
            try {
                log.debug("Simulating learning, sleeping for {}s", learnTime);
                Thread.sleep((long) (learnTime * 1000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Sleep interrupted for courseware {}, exiting", coursewareId);
                return false;
            }

            // Upload progress to server
            String uri = ConfigLoader.rawUrl("uploadLearn") + "?_t=" + System.currentTimeMillis();
            log.info("Uploading - courseware: {}, exitplaytime: {}s, learntime: {}s",
                    coursewareId, finalExitPlaytime, learnTime);
            WebUtil.sendPostRequest(
                    ConfigLoader.rawUrl("baseUrl"), uri, headers, requestBody, String.class);

            // Check if courseware is now passed
            LessonDetailResp lessonDetailResp = queryService.getLessonInfo(lessonId, token);
            if (lessonDetailResp == null || lessonDetailResp.getData() == null) {
                log.warn("Failed to fetch lesson details during upload check, retrying");
                continue;
            }

            for (UserLearnCourseWareVOList info : lessonDetailResp.getData().getUserlearncoursewareVOList()) {
                if (info.getCoursewareId().equals(coursewareId)) {
                    if (info.getPassed() != null && info.getPassed() == LearningConstants.PASS_CHECK) {
                        // Courseware passed successfully
                        isLearning = false;
                        isFinished = true;
                        log.info("Courseware {} PASSED (exitplaytime: {}s)", coursewareId, info.getExitplaytime());
                    } else {
                        // Check if progress has stalled (no improvement)
                        log.debug("Courseware {} progress: {}%, learntime: {}s",
                                coursewareId, info.getLearnProgress(), learnTime);

                        if (Objects.equals(info.getLearnProgress(), learnProgressHistory)) {
                            retryTimes++;
                            log.warn("Courseware {} progress stalled ({} -> {}) after {} retries (max: {})",
                                    coursewareId, learnProgressHistory, info.getLearnProgress(),
                                    retryTimes, LearningConstants.MAX_RETRY_STALL);
                        } else {
                            retryTimes = 0;  // Reset on improvement
                        }

                        if (retryTimes >= LearningConstants.MAX_RETRY_STALL) {
                            log.warn("Courseware {} progress stalled, skipping", coursewareId);
                            isLearning = false;
                        }

                        learnProgressHistory = info.getLearnProgress() != null
                                ? info.getLearnProgress() : learnProgressHistory;
                    }
                    break;
                }
            }
        }

        return isFinished;
    }

    /**
     * Retrieves the recorded exitplaytime from server for breakpoint resume.
     *
     * @param lessonId     Parent lesson ID
     * @param coursewareId Courseware ID
     * @param token        Mobile API token
     * @return Exit playtime in seconds, or 0.0 if not found
     */
    private double getExitPlaytimeFromServer(Long lessonId, Long coursewareId, String token) {
        try {
            LessonDetailResp resp = queryService.getLessonInfo(lessonId, token);
            if (resp != null && resp.getData() != null) {
                for (UserLearnCourseWareVOList info : resp.getData().getUserlearncoursewareVOList()) {
                    if (coursewareId.equals(info.getCoursewareId())) {
                        double exitTime = info.getExitplaytime() != null ? info.getExitplaytime() : 0.0;
                        log.debug("Server exitplaytime for courseware {}: {}s", coursewareId, exitTime);
                        return exitTime;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get exitplaytime from server for courseware {}: {}",
                    coursewareId, e.getMessage());
        }
        return 0.0;
    }

    /**
     * Checks if the specified course type has met its learning hour requirement.
     *
     * @param lessonType   "ElectiveSubject" or "MajorSubject"
     * @param periodDataRU Current period data with totals and goals
     * @return true if remaining hours <= 0 (requirement satisfied)
     */
    private boolean isLessonTypePassed(String lessonType, PeriodDataRU periodDataRU) {
        if ("ElectiveSubject".equals(lessonType)) {
            float remaining = periodDataRU.getSelfLearningGoal() - periodDataRU.getSelfLearningTotal();
            return remaining <= 0f;
        } else if ("MajorSubject".equals(lessonType)) {
            float remaining = periodDataRU.getGroupLearningGoal() - periodDataRU.getGroupLearningTotal();
            return remaining <= 0f;
        } else {
            throw new RuntimeException("Unsupported lesson type: " + lessonType);
        }
    }

    /**
     * Converts Map&lt;String, String&gt; to Map&lt;String, List&lt;String&gt;&gt;
     * as required by WebUtil for HTTP headers.
     *
     * @param source Map with single values
     * @return Map with values wrapped in lists
     */
    private Map<String, List<String>> toMultiValueMap(Map<String, String> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        source.forEach((k, v) -> result.put(k, List.of(v)));
        return result;
    }

    /**
     * Converts a String ID to Long safely.
     *
     * @param id String ID value
     * @return Long ID
     */
    private Long toLong(String id) {
        return Long.valueOf(id);
    }

    /**
     * Checks if a lesson is marked as passed (null-safe).
     *
     * @param isPass Pass status from lesson data (null, 0, or 1)
     * @return true if isPass equals PASS_CHECK
     */
    private boolean isPassed(Integer isPass) {
        return isPass != null && isPass == LearningConstants.PASS_CHECK;
    }
}
