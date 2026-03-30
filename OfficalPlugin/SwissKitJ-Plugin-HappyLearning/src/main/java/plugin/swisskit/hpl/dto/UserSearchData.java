package plugin.swisskit.hpl.dto;


import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

/**
 * User search data containing period learning requirements.
 *
 * @since 2026-03-19
 */
@Data
public class UserSearchData {
    @JSONField(name = "periodDataRU")
    private PeriodDataRU periodDataRU;
}
