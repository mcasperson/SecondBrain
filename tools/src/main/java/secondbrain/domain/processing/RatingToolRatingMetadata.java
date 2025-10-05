package secondbrain.domain.processing;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import secondbrain.domain.config.LocalConfigFilteredItem;
import secondbrain.domain.context.EnvironmentSettings;
import secondbrain.domain.context.HashMapEnvironmentSettings;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.tooldefs.MetaObjectResult;
import secondbrain.domain.tooldefs.MetaObjectResults;
import secondbrain.domain.tools.rating.RatingTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@ApplicationScoped
public class RatingToolRatingMetadata implements RatingMetadata {
    public static final String FILTER_RATING_META = "FilterRating";

    @Inject
    private RatingTool ratingTool;

    @Inject
    private Logger logger;

    @Override
    public MetaObjectResults getMetadata(
            final String toolName,
            final Map<String, String> environmentSettings,
            final RagDocumentContext<?> activity,
            final LocalConfigFilteredItem parsedArgs) {
        final List<MetaObjectResult> metadata = new ArrayList<>();

        // build the environment settings
        final EnvironmentSettings envSettings = new HashMapEnvironmentSettings(environmentSettings)
                .add(RatingTool.RATING_DOCUMENT_CONTEXT_ARG, activity.document())
                .addToolCall(toolName + "[" + activity.id() + "]");

        if (StringUtils.isNotBlank(parsedArgs.getContextFilterQuestion())) {
            final int filterRating = Try.of(() -> ratingTool.call(envSettings, parsedArgs.getContextFilterQuestion(), List.of()).getResponse())
                    .map(rating -> Integer.parseInt(rating.trim()))
                    .onFailure(e -> logger.warning("Failed to get Salesforce rating for email " + activity.id() + ": " + ExceptionUtils.getRootCauseMessage(e)))
                    // Ratings are provided on a best effort basis, so we ignore any failures
                    .recover(ex -> parsedArgs.getDefaultRating())
                    .get();

            metadata.add(new MetaObjectResult(FILTER_RATING_META, filterRating));
        }

        return new MetaObjectResults(
                metadata,
                toolName + "-" + activity.id() + ".json",
                activity.id());
    }
}
