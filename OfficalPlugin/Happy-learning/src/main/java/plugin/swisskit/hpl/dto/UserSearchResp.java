package plugin.swisskit.hpl.dto;


import lombok.Data;

/**
 * User search API response.
 *
 * @since 2026-03-19
 */
@Data
public class UserSearchResp {
    private Integer status;
    private String code;
    private String msg;
    private UserSearchData data;
}
