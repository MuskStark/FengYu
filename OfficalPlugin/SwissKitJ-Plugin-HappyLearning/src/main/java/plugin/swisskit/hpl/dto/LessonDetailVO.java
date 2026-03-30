package plugin.swisskit.hpl.dto;

import lombok.Data;

import java.util.List;

/**
 * Lesson detail VO containing lesson metadata.
 * Maps to the "lessonDetailVO" field in lesson detail API response.
 *
 * @since 2026-03-25
 */
@Data
public class LessonDetailVO {
    private Long lessonId;
    private String name;
    private Float classhour;
    private Integer letime;
    private Integer isonline;
    private String picfilename;
    private Long picfileId;
    private Integer learnpersons;
    private Integer evttotalmark;
    private Integer evtpersons;
    private Float starlevel;
    private String createdate;
    private Integer equipment;
    private Integer ispass;
    private List<ResourceCategoryMngVO> resourcecategoryMngVOList;
    private String brief;
    private Integer isorderlearn;
    private Integer commentcount;
    private Object teachers;
    private Integer iscloseplay;
    private Integer istimespeedplay;
}
