package secondbrain.domain.exceptions;

import java.util.List;
import java.util.Objects;

/**
 * Represents a failure to call an LLM, which may include multiple causes (e.g. multiple failed attempts).
 */
public class LlmCallFailure extends RuntimeException implements ExternalException {
    private final List<Throwable> causes;

    public LlmCallFailure() {
        super();
        causes = List.of();
    }

    public LlmCallFailure(final String message) {
        super(message);
        causes = List.of();
    }

    public LlmCallFailure(final String message, final Throwable cause) {
        super(message, cause);
        causes = cause == null ? List.of() : List.of(cause);
    }

    public LlmCallFailure(final String message, final List<Throwable> causes) {
        super(message, causes == null || causes.isEmpty() ? null : causes.getFirst());
        this.causes = List.copyOf(Objects.requireNonNullElse(causes, List.of()))
                .stream()
                .filter(Objects::nonNull)
                .toList();
    }

    public LlmCallFailure(final Throwable cause) {
        super(cause);
        this.causes = cause == null ? List.of() : List.of(cause);
    }

    public List<Throwable> getCauses() {
        return causes;
    }
}
