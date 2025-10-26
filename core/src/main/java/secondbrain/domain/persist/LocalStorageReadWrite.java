package secondbrain.domain.persist;

import java.util.Optional;

/**
 * Represents the bare minimum read/write operations for local storage.
 */
public interface LocalStorageReadWrite {
    Optional<String> getString(String tool, String source, String promptHash);

    String putString(String tool, String source, String promptHash, Long timestamp, String value);
}
