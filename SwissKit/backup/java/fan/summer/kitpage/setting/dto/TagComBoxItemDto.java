package fan.summer.kitpage.setting.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TagComBoxItemDto {
    private Long id;
    private String tag;

    @Override
    public String toString() {
        return tag;
    }
}
