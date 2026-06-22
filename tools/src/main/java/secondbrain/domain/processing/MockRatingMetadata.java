package secondbrain.domain.processing;

import jakarta.enterprise.context.ApplicationScoped;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.config.LocalConfigFilteredItem;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.tooldefs.MetaObjectResult;
import secondbrain.domain.tooldefs.MetaObjectResults;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A mock implementation of the {@link RatingMetadata} interface used for testing purposes.
 * When {@code mockRating} is set, it returns a {@link RatingResults} containing metadata
 * with the configured rating value. When {@code mockRating} is {@code null}, it returns
 * {@link Optional#empty()}, meaning no filtering is applied.
 * <p>
 * This implementation is not exposed by any producer. It is only intended to be used by tests.
 */
@ApplicationScoped
public class MockRatingMetadata implements RatingMetadata {

    @Nullable
    private Integer mockRating;

    /**
     * Sets the rating value to return from {@link #getMetadata}.
     *
     * @param mockRating the rating to return, or {@code null} to return empty (no filtering).
     */
    public void setMockRating(@Nullable final Integer mockRating) {
        this.mockRating = mockRating;
    }

    @Override
    public Optional<RatingResults> getMetadata(
            final String toolName,
            final Map<String, String> environmentSettings,
            final RagDocumentContext<?> activity,
            final LocalConfigFilteredItem parsedArgs) {
        if (mockRating == null) {
            return Optional.empty();
        }

        final MetaObjectResults meta = new MetaObjectResults(
                List.of(new MetaObjectResult(
                        RatingToolRatingMetadata.FILTER_RATING_META,
                        mockRating,
                        Objects.requireNonNullElse(activity.id(), "unknownId"),
                        toolName)),
                toolName + "-" + activity.getId() + ".json",
                activity.getId());

        return Optional.of(new RatingResults(meta, List.of()));
    }
}
