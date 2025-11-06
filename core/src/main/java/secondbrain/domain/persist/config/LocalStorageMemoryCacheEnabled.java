package secondbrain.domain.persist.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

@ApplicationScoped
public class LocalStorageMemoryCacheEnabled {
    @Inject
    @ConfigProperty(name = "sb.cache.memorycacheenabled")
    private Optional<String> memoryCacheEnabled;

    public boolean isMemoryCacheEnabled() {
        return Boolean.parseBoolean(memoryCacheEnabled.orElse("true"));
    }
}

