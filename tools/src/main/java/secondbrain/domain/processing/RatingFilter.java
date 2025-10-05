package secondbrain.domain.processing;

import secondbrain.domain.config.LocalConfigFilteredParent;
import secondbrain.domain.context.RagDocumentContext;

import java.util.List;

/**
 * Defines a service used to filter document contexts based on ratings.
 */
public interface RatingFilter {
    boolean contextMeetsRating(
            RagDocumentContext<?> activity,
            LocalConfigFilteredParent parsedArgs);

    <T> List<RagDocumentContext<T>> contextMeetsRating(
            List<RagDocumentContext<T>> activity,
            LocalConfigFilteredParent parsedArgs);
}
