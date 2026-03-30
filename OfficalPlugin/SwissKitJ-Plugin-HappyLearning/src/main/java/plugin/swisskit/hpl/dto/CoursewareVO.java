package plugin.swisskit.hpl.dto;

import lombok.Data;

/**
 * Courseware VO containing courseware metadata.
 * Maps to the "coursewareVO" field inside userlearncoursewareVOList.
 *
 * @since 2026-03-25
 */
@Data
public class CoursewareVO {
    private Long lessonId;
    private Long coursewareId;
    private String name;
    private Integer display;
    private Integer standard;
    private String playtimeformat;
    private Integer ordernum;
    private String httpenterurlpc;
    private String httpenterurlmobile;
    private String httpenterurlpcstandard;
    private String httpenterurlmobilestandard;
    private String httpenterurlpchigh;
    private String httpenterurlmobilehigh;
    private String httpenterurlpcsuper;
    private String httpenterurlmobilesuper;
    private String httpenterurlpcaudio;
    private String httpenterurlmobileaudio;
    private Integer selfnav;
}