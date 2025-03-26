package secondbrain.infrastructure.gong;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongRecords(
        int totalRecords,
        int currentPageSize,
        int currentPageNumber,
        @Nullable String cursor
) {
}
