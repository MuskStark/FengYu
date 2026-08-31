package fan.summer.fengyu.account;

import fan.summer.fengyu.setup.CryptoUtil;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Encrypts Store OAuth tokens before they enter the local database. */
@Converter
public class CloudTokenConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String token) {
        return token == null || token.isBlank() ? token : CryptoUtil.encrypt(token);
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        return CryptoUtil.decrypt(stored);
    }
}
