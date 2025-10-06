package secondbrain.domain.hooks;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.limit.ListLimiter;

import java.util.List;

/**
 * A hook that limits the size of the context passed to the LLM. And example of this hook is
 * limiting the context size used for categorization for faster filtering.
 */
@ApplicationScoped
public class LimitContextSizeHook implements PreProcessingHook {
    @Inject
    @ConfigProperty(name = "sb.limitcontextsize.chars", defaultValue = "-1")
    private Integer maxSize;

    @Inject
    private ListLimiter limiter;

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public <T> List<RagDocumentContext<T>> process(final String toolName, final List<RagDocumentContext<T>> ragDocumentContexts) {
        if (ragDocumentContexts == null) {
            return List.of();
        }

        if (maxSize <= 0) {
            return ragDocumentContexts;
        }

        return limiter.limitListContent(ragDocumentContexts, RagDocumentContext::document, maxSize);
    }
}
