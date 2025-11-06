package secondbrain.domain.persist.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

@ApplicationScoped
public class LocalStorageMemoryCacheFileLimit {
    private static final int MAX_LOCAL_CACHE_ENTRIES = 10000;

    @Inject
    @ConfigProperty(name = "sb.cache.memorylimitfiles")
    private Optional<String> memoryLimit;

    public int getMemoryCacheFileLimit() {
        return NumberUtils.toInt(memoryLimit.orElse(MAX_LOCAL_CACHE_ENTRIES + ""), MAX_LOCAL_CACHE_ENTRIES);
    }
}
