package secondbrain.domain.persist.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

@ApplicationScoped
public class LocalStorageMemoryCacheSizeLimit {
    private static final long MAX_LOCAL_CACHE_SIZE_BYTES = 1000L * 1024L * 1024L;

    @Inject
    @ConfigProperty(name = "sb.cache.memorylimitbytes")
    private Optional<String> memoryLimit;

    public long getMemoryCacheSizeLimit() {
        return NumberUtils.toLong(memoryLimit.orElse(MAX_LOCAL_CACHE_SIZE_BYTES + ""), MAX_LOCAL_CACHE_SIZE_BYTES);
    }
}
