package secondbrain.domain.persist;

import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Represents the bare minimum read/write operations for local storage.
 */
public interface LocalStorageReadWrite {
    Optional<String> getString(String tool, String source, String promptHash);

    String putString(String tool, String source, String promptHash, @Nullable Long timestamp, String value);
}
