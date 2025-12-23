package secondbrain.domain.processing;

import org.jspecify.annotations.Nullable;

public interface ContextLabelCallback<T> {
    String getContextLabel(@Nullable T item);
}
