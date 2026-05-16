package fan.summer.database.entity.setting.email;

import lombok.Data;

@Data
public class SwissKitSettingEmailEntity {
    private Integer id;
    private String email;
    private String password;
    private String smtpAddress;
    private Integer smtpPort;
    private Boolean needTLS;
    private Boolean needSSL;
    private String fromAddress;
}
