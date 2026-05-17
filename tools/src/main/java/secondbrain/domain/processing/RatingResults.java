package secondbrain.domain.processing;

import org.jspecify.annotations.Nullable;
import secondbrain.domain.tooldefs.IntermediateResult;
import secondbrain.domain.tooldefs.MetaObjectResults;

import java.util.List;
import java.util.Objects;

/**
 * Represents the results of rating content
 * @param metadata The overall result of the rating process
 * @param intermediateResults The individual results of each judge LLM
 */
public record RatingResults(@Nullable MetaObjectResults metadata,
                            @Nullable List<IntermediateResult> intermediateResults) {
    public MetaObjectResults getMetadata() {
        return Objects.requireNonNullElse(metadata, new MetaObjectResults());
    }

    public List<IntermediateResult> getIntermediateResults() {
        return Objects.requireNonNullElse(intermediateResults, List.of());
    }
}
