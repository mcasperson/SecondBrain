package secondbrain.domain.processing;

import secondbrain.domain.config.LocalConfigFilteredParent;
import secondbrain.domain.context.RagDocumentContext;

import java.util.List;

public interface RatingFilter {
    boolean contextMeetsRating(
            RagDocumentContext<?> activity,
            LocalConfigFilteredParent parsedArgs);

    <T> List<RagDocumentContext<T>> contextMeetsRating(
            List<RagDocumentContext<T>> activity,
            LocalConfigFilteredParent parsedArgs);
}
