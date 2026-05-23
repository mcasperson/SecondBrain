package secondbrain.domain.persist;

import jakarta.enterprise.context.ApplicationScoped;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A simple in-memory mock implementation of LocalStorageReadWrite.
 */
@ApplicationScoped
public class MockLocalStorageReadWrite implements LocalStorageReadWrite {

    private final Map<String, String> store = new ConcurrentHashMap<>();

    private String buildKey(final String tool, final String source, final String promptHash) {
        return tool + "::" + source + "::" + promptHash;
    }

    @Override
    public Optional<String> getString(final String tool, final String source, final String promptHash) {
        return Optional.ofNullable(store.get(buildKey(tool, source, promptHash)));
    }

    @Override
    public String putString(final String tool, final String source, final String promptHash, @Nullable final Long timestamp, final String value) {
        store.put(buildKey(tool, source, promptHash), value);
        return value;
    }

    @Override
    public void purge() {
        store.clear();
    }
}

