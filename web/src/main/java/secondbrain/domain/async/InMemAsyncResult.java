package secondbrain.domain.async;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.collections4.map.PassiveExpiringMap;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * An in memory implementation of the {@link AsyncResults} interface.
 */
@ApplicationScoped
public class InMemAsyncResult implements AsyncResults {
    /**
     * This is an in memory cache of the results of the prompt handler.
     */
    private static final PassiveExpiringMap<String, String> RESULTS = new PassiveExpiringMap<>(
            new PassiveExpiringMap.ConstantTimeToLiveExpirationPolicy<>(5, TimeUnit.MINUTES)
    );

    @Override
    public void addResult(final String key, final String value) {
        synchronized (RESULTS) {
            RESULTS.put(key, value);
        }
    }

    @Override
    public Optional<String> getResult(final String key) {
        synchronized (RESULTS) {
            return Optional.ofNullable(RESULTS.get(key));
        }
    }
}
