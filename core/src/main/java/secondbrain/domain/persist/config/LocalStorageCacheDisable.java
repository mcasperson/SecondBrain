package secondbrain.domain.persist.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

@ApplicationScoped
public class LocalStorageCacheDisable {
    @Inject
    @ConfigProperty(name = "sb.cache.disable")
    private Optional<String> disable;

    public boolean isDisabled() {
        return disable.isPresent() && Boolean.parseBoolean(disable.get());
    }
}
