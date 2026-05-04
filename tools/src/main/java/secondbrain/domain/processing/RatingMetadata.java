package secondbrain.domain.processing;

import secondbrain.domain.config.LocalConfigFilteredItem;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.tooldefs.MetaObjectResults;

import java.util.Map;
import java.util.Optional;

/**
 * Defines a service used to assign a rating to a document context based on specific criteria.
 */
public interface RatingMetadata {
    Optional<RatingResults> getMetadata(
            final String toolName,
            final Map<String, String> environmentSettings,
            final RagDocumentContext<?> activity,
            final LocalConfigFilteredItem parsedArgs);
}
