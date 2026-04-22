package secondbrain.infrastructure.dovetail;

import org.jspecify.annotations.Nullable;
import secondbrain.infrastructure.dovetail.api.DovetailDataItem;

import java.util.List;

public interface DovetailClient {

    /**
     * Lists all data items from the Dovetail API, filtered by an optional date range.
     *
     * @param apiKey       the Dovetail API key
     * @param fromDateTime optional ISO-8601 date-time for the lower bound of created_at
     * @param toDateTime   optional ISO-8601 date-time for the upper bound of created_at
     * @return a list of data items
     */
    List<DovetailDataItem> getDataItems(
            String apiKey,
            @Nullable String fromDateTime,
            @Nullable String toDateTime);

    /**
     * Exports a single data item as markdown.
     *
     * @param apiKey the Dovetail API key
     * @param id     the data item ID
     * @return the markdown content
     */
    String exportDataItemAsMarkdown(
            String apiKey,
            String id);
}

