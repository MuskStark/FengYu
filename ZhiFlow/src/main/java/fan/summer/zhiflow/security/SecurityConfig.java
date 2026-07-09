package fan.summer.zhiflow.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Noop auth/security implementations as Spring beans. When login is implemented,
 * this config will switch to real AuthProvider/SecurityContext beans (or use @ConditionalOnProperty
 * to choose between local-offline and authenticated modes).
 */
@Configuration
public class SecurityConfig {

    @Bean
    public AuthProvider authProvider() {
        return new NoopAuthProvider();
    }

    @Bean
    public SecurityContext securityContext() {
        return new NoopSecurityContext();
    }
}
