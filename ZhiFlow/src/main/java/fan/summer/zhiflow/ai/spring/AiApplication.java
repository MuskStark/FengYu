package fan.summer.zhiflow.ai.spring;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Spring Boot application for the headless ZhiFlow backend.
 *
 * <p>Scans {@code fan.summer.zhiflow}, so it picks up the web controllers, the plugin registry
 * service, and the AI {@code ChatModel} {@code @Bean}s. With {@code spring-boot-starter-web} on the
 * classpath it boots an embedded servlet web server (Tomcat); {@link fan.summer.zhiflow.HeadlessLauncher}
 * binds it to loopback via {@code --server.address}/{@code --server.port} args.
 */
@SpringBootApplication
@ComponentScan(basePackages = "fan.summer.zhiflow")
public class AiApplication {
}
