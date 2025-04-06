package secondbrain.domain.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class MockConfig {
    @Inject
    @ConfigProperty(name = "sb.infrastructure.mock", defaultValue = "false")
    private String mock;

    public boolean isMock() {
        return Boolean.parseBoolean(mock.toLowerCase());
    }
}
