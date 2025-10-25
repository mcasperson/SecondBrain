package secondbrain.domain.processing;

public interface ContextLabelCallback<T> {
    String getContextLabel(T item);
}
