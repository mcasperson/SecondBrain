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
        final MetaObjectResults fixedMetadata = Objects.requireNonNullElse(activity.metadata(), new MetaObjectResults());

        // If the item was not filtered, then return true
        if (!fixedMetadata.hasName(FILTER_RATING_META)) {
            return true;
        }

        return fixedMetadata.getIntValueByName(FILTER_RATING_META, parsedArgs.getDefaultRating())
                >= parsedArgs.getContextFilterMinimumRating();
    }

    @Override
    public <T> List<RagDocumentContext<T>> contextMeetsRating(List<RagDocumentContext<T>> activity, LocalConfigFilteredParent parsedArgs) {
        return activity
                .stream()
                .filter(ticket -> contextMeetsRating(ticket, parsedArgs))
                .toList();
    }
}
