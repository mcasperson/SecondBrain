package secondbrain.domain.processing;

import jakarta.enterprise.context.ApplicationScoped;
import secondbrain.domain.config.LocalConfigFilteredParent;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.tooldefs.MetaObjectResults;

import java.util.List;
import java.util.Objects;

@ApplicationScoped
public class RatingToolRatingFilter implements RatingFilter {
    public static final String FILTER_RATING_META = "FilterRating";

    @Override
    public boolean contextMeetsRating(final RagDocumentContext<?> activity, final LocalConfigFilteredParent parsedArgs) {
        if (activity == null) {
            return false;
        }

        final MetaObjectResults fixedMetadata = Objects.requireNonNullElse(activity.metadata(), new MetaObjectResults());

        // If the item was not filtered, then return true
        if (!fixedMetadata.hasName(FILTER_RATING_META)) {
            return true;
        }

        if (parsedArgs.isContextFilterGreaterThan()) {
            return fixedMetadata.getIntValueByName(FILTER_RATING_META, parsedArgs.getDefaultRating())
                    >= parsedArgs.getContextFilterMinimumRating();
        }

        return fixedMetadata.getIntValueByName(FILTER_RATING_META, parsedArgs.getDefaultRating())
                <= parsedArgs.getContextFilterMinimumRating();
    }

    @Override
    public <T> List<RagDocumentContext<T>> contextMeetsRating(final List<RagDocumentContext<T>> activity, final LocalConfigFilteredParent parsedArgs) {
        if (activity == null || activity.isEmpty()) {
            return List.of();
        }

        return activity
                .stream()
                .filter(ticket -> contextMeetsRating(ticket, parsedArgs))
                .toList();
    }
}
