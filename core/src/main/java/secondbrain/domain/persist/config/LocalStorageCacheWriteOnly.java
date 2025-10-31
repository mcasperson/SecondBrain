package secondbrain.domain.persist.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

@ApplicationScoped
public class LocalStorageCacheWriteOnly {
    @Inject
    @ConfigProperty(name = "sb.cache.writeonly")
    private Optional<String> writeOnly;

    public boolean isWriteOnly() {
        return writeOnly.isPresent() && Boolean.parseBoolean(writeOnly.get());
    }
}

