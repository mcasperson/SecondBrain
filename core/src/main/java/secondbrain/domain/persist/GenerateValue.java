package secondbrain.domain.persist;

import org.jspecify.annotations.Nullable;

public interface GenerateValue<T> {
    @Nullable T generate();
}
