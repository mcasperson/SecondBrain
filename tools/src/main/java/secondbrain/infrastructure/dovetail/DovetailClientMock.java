package secondbrain.infrastructure.dovetail;

import jakarta.enterprise.context.ApplicationScoped;
import org.jspecify.annotations.Nullable;
import secondbrain.infrastructure.dovetail.api.DovetailDataItem;
import secondbrain.infrastructure.dovetail.api.DovetailDataProject;

import java.util.List;

/**
 * A mock implementation of the {@link DovetailClient} interface for use in tests or when mocking is enabled.
 */
@ApplicationScoped
public class DovetailClientMock implements DovetailClient {

    public static final DovetailDataItem MOCK_DATA_ITEM = new DovetailDataItem(
            "mock-id-1",
            "data",
            "Mock Dovetail Data Item",
            new DovetailDataProject("mock-project-id", "Mock Project"),
            "2024-06-15T10:30:00Z",
            false,
            null);

    @Override
    public List<DovetailDataItem> getDataItems(
            final String apiKey,
            @Nullable final String fromDateTime,
            @Nullable final String toDateTime) {
        return List.of(MOCK_DATA_ITEM);
    }

    @Override
    public String exportDataItemAsMarkdown(
            final String apiKey,
            final String id) {
        return "# Mock Dovetail Export\n\nThis is a mock markdown export for item: " + id;
    }
}
