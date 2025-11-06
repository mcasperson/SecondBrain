package secondbrain.domain.persist.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

@ApplicationScoped
public class LocalStorageCacheDirectory {
    private static final String LOCAL_CACHE_DIR = "localcache";

    @Inject
    @ConfigProperty(name = "sb.cache.localdir")
    private Optional<String> localCache;

    public String getCacheDirectory() {
        return localCache.orElse(LOCAL_CACHE_DIR);
    }
}

