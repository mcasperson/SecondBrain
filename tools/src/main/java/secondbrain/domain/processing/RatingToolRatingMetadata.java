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
import secondbrain.domain.tooldefs.IntermediateResult;
import secondbrain.domain.tooldefs.MetaObjectResult;
import secondbrain.domain.tooldefs.MetaObjectResults;
import secondbrain.domain.tools.rating.RatingTool;

import java.util.*;
import java.util.logging.Logger;

@ApplicationScoped
public class RatingToolRatingMetadata implements RatingMetadata {
    public static final String FILTER_RATING_META = "FilterRating";

    @Inject
    private RatingTool ratingTool;

    @Inject
    private Logger logger;

    @Override
    public Optional<RatingResults> getMetadata(
            final String toolName,
            final Map<String, String> environmentSettings,
            final RagDocumentContext<?> activity,
            final LocalConfigFilteredItem parsedArgs) {
        // build the environment settings
        final EnvironmentSettings envSettings = new HashMapEnvironmentSettings(environmentSettings)
                .add(RatingTool.RATING_DOCUMENT_CONTEXT_ARG, activity.document())
                .add(RatingTool.RATING_ID_CONTEXT_ARG, Objects.requireNonNullElse(activity.id(), "unknownId"))
                .add(RatingTool.RATING_TOOL_CONTEXT_ARG, Objects.requireNonNullElse(activity.tool(), "unknownTool"))
                .addToolCall(toolName, Objects.requireNonNullElse(activity.id(), "unknownId"));

        if (StringUtils.isNotBlank(parsedArgs.getContextFilterQuestion())) {
            final RatingIntermediateResult filterRating = Try.of(() -> ratingTool.call(envSettings, parsedArgs.getContextFilterQuestion(), List.of()))
                    .map(rating -> new RatingIntermediateResult(
                            // convert the result into an integer
                            Integer.parseInt(rating.getResponse().trim()),
                            // Merge all the individual contexts into one list
                            rating.getIndividualContexts()
                                            .stream()
                                            .flatMap(context -> context.getIntermediateResults().stream())
                                            .toList()))
                    .onFailure(e -> logger.warning("Failed to get rating for document " + activity.id() + ": " + ExceptionUtils.getRootCauseMessage(e)))
                    // Ratings are provided on a best effort basis, so we ignore any failures
                    .recover(ex -> new RatingIntermediateResult(parsedArgs.getDefaultRating(), List.of()))
                    .get();

            final MetaObjectResults meta = new MetaObjectResults(
                    List.of(new MetaObjectResult(FILTER_RATING_META, filterRating.rating, activity.id(), toolName)),
                    toolName + "-" + activity.getId() + ".json",
                    activity.getId());

            return Optional.of(new RatingResults(meta, filterRating.intermediateResults));
        }

        return Optional.empty();
    }

    record RatingIntermediateResult(Integer rating, List<IntermediateResult> intermediateResults) {}
}
