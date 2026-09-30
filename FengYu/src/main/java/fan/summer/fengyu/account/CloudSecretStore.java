package fan.summer.fengyu.account;

import java.util.Optional;

/**
 * Credential storage backed by the operating system's secret facility (design
 * §7.2 / line 338+368: the cloud refresh token may only rest in the OS
 * Keychain / Credential Manager / Secret Service — never in the database).
 */
public interface CloudSecretStore {

    /** Stores (replacing) the secret under the given name; implementations must not persist when unavailable. */
    void save(String name, String value);

    /** Loads the secret, if present. */
    Optional<String> load(String name);

    /** Removes the secret; missing names are not an error. */
    void delete(String name);

    /** Whether this host actually has a usable OS credential store. */
    boolean available();
}
