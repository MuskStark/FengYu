package fan.summer.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for string validation and manipulation.
 *
 * @author phoebej
 * @version 1.00
 * @Date 2026/3/8
 */
public abstract class StringUtil {
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$");

    /**
     * Validates if the given string is a valid email address.
     *
     * @param email the email address to validate
     * @return true if the email format is valid, false otherwise
     */
    public static boolean checkEmail(String email) {
        if (email == null || email.isEmpty()) {
            return false;
        }
        Matcher matcher = EMAIL_PATTERN.matcher(email);
        return matcher.matches();
    }
}
