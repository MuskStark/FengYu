package plugin.swisskit.hpl.service;

/**
 * Constants for HappyLearning plugin.
 * Extracts magic numbers from the codebase for better maintainability.
 */
public class LearningConstants {

    // Lesson type codes (API contract values)
    public static final int LESSON_TYPE_ELECTIVE = 1;
    public static final int LESSON_TYPE_MAJOR = 64;

    // HTTP status codes
    public static final int HTTP_OK = 200;

    // Pagination
    public static final int PAGE_SIZE = 16;

    // Retry settings
    public static final int MAX_RETRY_STALL = 2;

    // Learning time simulation
    public static final double LEARN_TIME_MIN = 60.0;
    public static final double LEARN_TIME_MAX = 61.0;
    public static final float EXIT_TIME_DEDUCTION = 0.99999f;

    // Completion check
    public static final float PASS_CHECK = 1.0f;

    // Default values
    public static final int DEFAULT_PASSED = 0;
    public static final int DEFAULT_FINISHED = 0;
    public static final int DEFAULT_LEARN_COUNT = 0;

    private LearningConstants() {
        // Utility class
    }
}
