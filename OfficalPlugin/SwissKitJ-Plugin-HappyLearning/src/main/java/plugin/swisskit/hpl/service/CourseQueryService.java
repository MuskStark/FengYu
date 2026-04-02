package plugin.swisskit.hpl.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import plugin.swisskit.hpl.dto.*;
import plugin.swisskit.hpl.util.ConfigLoader;
import plugin.swisskit.hpl.util.WebUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Course query service - handles all course and personal info query operations.
 *
 * @author summer
 * @version 1.00
 * @Date 2026/4/2
 */
public class CourseQueryService {

    private static final Logger log = LoggerFactory.getLogger(CourseQueryService.class);

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

        StringBuilder queryBuilder = new StringBuilder();
        queryBuilder.append("openSchedule=1");

        String uri = ConfigLoader.rawUrl("personInfo") + "?" + queryBuilder;

        return WebUtil.sendPostRequest(
                ConfigLoader.rawUrl("baseUrl"), uri, headers, null, UserSearchResp.class);
    }

    /**
     * Query course details (portalGet)
     *
     * @param lessonId Course ID
     * @param token    Mobile token
     */
    public LessonDetailResp getLessonInfo(Long lessonId, String token) {
        Map<String, String> headers =
                ConfigLoader.headers("portalGet", Map.of("M0biletoken", token));

        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("_t", List.of(String.valueOf(System.currentTimeMillis())));
        params.put("m0biletoken", List.of(token));
        params.put("lessonId", List.of(String.valueOf(lessonId)));
        params.put("businessId", List.of(String.valueOf(lessonId)));
        params.put("businesstype", List.of(String.valueOf(LearningConstants.LESSON_TYPE_ELECTIVE)));

        return WebUtil.sendGetRequest(
                ConfigLoader.rawUrl("baseUrl"),
                ConfigLoader.rawUrl("lessonInfo"),
                headers, params, LessonDetailResp.class);
    }

    /**
     * Query course list (paginated, mainSiteGet)
     *
     * @param page       Page number
     * @param lessonType Lesson type (e.g., "ElectiveSubject", "MajorSubject")
     * @param token      Mobile token
     * @param cookie     Login Cookie
     */
    public LessonSearchResp queryLessonList(Integer page, String lessonType,
                                              String token, String cookie) {
        Map<String, String> dynamics = new LinkedHashMap<>();
        dynamics.put("M0biletoken", token);
        dynamics.put("Cookie", cookie);
        Map<String, String> headers = ConfigLoader.headers("mainSiteGet", dynamics);

        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("_t", List.of(String.valueOf(System.currentTimeMillis())));
        params.put("pageNumber", List.of(String.valueOf(page)));
        params.put("pageSize", List.of(String.valueOf(LearningConstants.PAGE_SIZE)));
        params.put("classHourTypes", List.of(lessonType));

        return WebUtil.sendGetRequest(
                ConfigLoader.rawUrl("baseUrl"),
                ConfigLoader.rawUrl("lessonList"),
                headers, params, LessonSearchResp.class);
    }

    /**
     * Convert Map&lt;String, String&gt; to Map&lt;String, List&lt;String&gt;&gt; required by WebUtil
     */
    private Map<String, List<String>> toMultiValueMap(Map<String, String> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        source.forEach((k, v) -> result.put(k, List.of(v)));
        return result;
    }
}
