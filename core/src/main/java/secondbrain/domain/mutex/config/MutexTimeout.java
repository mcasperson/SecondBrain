package secondbrain.domain.mutex.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

@ApplicationScoped
public class MutexTimeout {
    private static final long DEFAULT_TIMEOUT = 1000 * 60 * 30;

    @Inject
    @ConfigProperty(name = "sb.mutex.defaultTimeout")
    private Optional<String> defaultTimeout;

    public long getDefaultTimeout() {
        return NumberUtils.toLong(defaultTimeout.orElse(String.valueOf(DEFAULT_TIMEOUT)), DEFAULT_TIMEOUT);
    }
}
