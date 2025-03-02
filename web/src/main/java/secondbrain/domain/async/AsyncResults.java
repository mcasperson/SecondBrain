package secondbrain.domain.async;

import java.util.Optional;

public interface AsyncResults {
    void addResult(String key, String value);

    Optional<String> getResult(String key);
}
