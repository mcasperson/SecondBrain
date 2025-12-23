package secondbrain.domain.processing;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;
import secondbrain.domain.config.LocalConfigFilteredParent;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.data.IdData;
import secondbrain.domain.tooldefs.MetaObjectResult;
import secondbrain.domain.tooldefs.MetaObjectResults;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("NullAway")
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(RatingToolRatingFilter.class)
class RatingToolRatingFilterTest {

    @Inject
    private RatingToolRatingFilter ratingToolRatingFilter;

    @Test
    void testContextMeetsRating_NullActivity() {
        LocalConfigFilteredParent config = createConfig(5, false, 0);
        assertFalse(ratingToolRatingFilter.contextMeetsRating((RagDocumentContext<?>) null, config));
    }

    @Test
    void testContextMeetsRating_NoFilterRatingMeta() {
        LocalConfigFilteredParent config = createConfig(5, false, 0);
        RagDocumentContext<TestTask> context = createContext("test-1", new MetaObjectResults());
        assertTrue(ratingToolRatingFilter.contextMeetsRating(context, config));
    }

    @Test
    void testContextMeetsRating_MeetsMinimumRating() {
        LocalConfigFilteredParent config = createConfig(5, false, 0);
        MetaObjectResults metadata = new MetaObjectResults();
        metadata.add(new MetaObjectResult(RatingToolRatingFilter.FILTER_RATING_META, 7));
        RagDocumentContext<TestTask> context = createContext("test-1", metadata);
        assertTrue(ratingToolRatingFilter.contextMeetsRating(context, config));
    }

    @Test
    void testContextMeetsRating_BelowMinimumRating() {
        LocalConfigFilteredParent config = createConfig(5, false, 0);
        MetaObjectResults metadata = new MetaObjectResults();
        metadata.add(new MetaObjectResult(RatingToolRatingFilter.FILTER_RATING_META, 3));
        RagDocumentContext<TestTask> context = createContext("test-1", metadata);
        assertFalse(ratingToolRatingFilter.contextMeetsRating(context, config));
    }

    @Test
    void testContextMeetsRating_UpperLimit_MeetsRating() {
        LocalConfigFilteredParent config = createConfig(5, true, 0);
        MetaObjectResults metadata = new MetaObjectResults();
        metadata.add(new MetaObjectResult(RatingToolRatingFilter.FILTER_RATING_META, 3));
        RagDocumentContext<TestTask> context = createContext("test-1", metadata);
        assertTrue(ratingToolRatingFilter.contextMeetsRating(context, config));
    }

    @Test
    void testContextMeetsRating_UpperLimit_ExceedsRating() {
        LocalConfigFilteredParent config = createConfig(5, true, 0);
        MetaObjectResults metadata = new MetaObjectResults();
        metadata.add(new MetaObjectResult(RatingToolRatingFilter.FILTER_RATING_META, 7));
        RagDocumentContext<TestTask> context = createContext("test-1", metadata);
        assertFalse(ratingToolRatingFilter.contextMeetsRating(context, config));
    }

    @Test
    void testContextMeetsRating_List_NullOrEmpty() {
        LocalConfigFilteredParent config = createConfig(5, false, 0);
        assertTrue(ratingToolRatingFilter.contextMeetsRating((List<RagDocumentContext<TestTask>>) null, config).isEmpty());
        assertTrue(ratingToolRatingFilter.contextMeetsRating(List.of(), config).isEmpty());
    }

    @Test
    void testContextMeetsRating_List_FiltersCorrectly() {
        LocalConfigFilteredParent config = createConfig(5, false, 0);

        MetaObjectResults meta1 = new MetaObjectResults();
        meta1.add(new MetaObjectResult(RatingToolRatingFilter.FILTER_RATING_META, 7));

        MetaObjectResults meta2 = new MetaObjectResults();
        meta2.add(new MetaObjectResult(RatingToolRatingFilter.FILTER_RATING_META, 3));

        MetaObjectResults meta3 = new MetaObjectResults();
        meta3.add(new MetaObjectResult(RatingToolRatingFilter.FILTER_RATING_META, 5));

        List<RagDocumentContext<TestTask>> contexts = List.of(
                createContext("test-1", meta1),
                createContext("test-2", meta2),
                createContext("test-3", meta3)
        );

        List<RagDocumentContext<TestTask>> result = ratingToolRatingFilter.contextMeetsRating(contexts, config);
        assertEquals(2, result.size());
        assertEquals("test-1", result.get(0).id());
        assertEquals("test-3", result.get(1).id());
    }

    private LocalConfigFilteredParent createConfig(final int minRating, final boolean isUpperLimit, final int defaultRating) {
        return new LocalConfigFilteredParent() {
            @Override
            public Integer getContextFilterMinimumRating() {
                return minRating;
            }

            @Override
            public boolean isContextFilterUpperLimit() {
                return isUpperLimit;
            }

            @Override
            public Integer getDefaultRating() {
                return defaultRating;
            }
        };
    }

    private RagDocumentContext<TestTask> createContext(final String id, final MetaObjectResults metadata) {
        return new RagDocumentContext<>(
                "toolName",
                "contextLabel",
                "document",
                List.of(),
                id,
                null,
                metadata,
                null,
                null,
                null
        );
    }

    private record TestTask(String id) implements IdData {
        @Override
        public String generateId() {
            return id;
        }
    }
}
