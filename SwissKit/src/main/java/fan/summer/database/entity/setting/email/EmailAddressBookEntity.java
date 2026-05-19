package fan.summer.database.entity.setting.email;

import lombok.Data;

@Data
public class EmailAddressBookEntity {
    private Integer id;
    private String emailAddress;
    private String nickname;
    private String tags;
}
