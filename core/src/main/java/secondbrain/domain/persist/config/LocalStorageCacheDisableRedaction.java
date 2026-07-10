package secondbrain.domain.persist.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

@ApplicationScoped
public class LocalStorageCacheDisableRedaction {
    @Inject
    @ConfigProperty(name = "sb.cache.disableredaction")
    private Optional<String> disableRedaction;

    public boolean isRedactionDisabled() {
        return disableRedaction.isPresent() && Boolean.parseBoolean(disableRedaction.get());
    }
}
