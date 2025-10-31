package secondbrain.domain.persist.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

@ApplicationScoped
public class LocalStorageCacheReadOnly {
    @Inject
    @ConfigProperty(name = "sb.cache.readonly")
    private Optional<String> readOnly;

    public boolean isReadOnly() {
        return readOnly.isPresent() && Boolean.parseBoolean(readOnly.get());
    }
}
