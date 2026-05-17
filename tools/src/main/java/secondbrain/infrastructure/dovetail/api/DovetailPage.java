package secondbrain.infrastructure.dovetail.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DovetailPage(
        @JsonProperty("total_count") int totalCount,
        @JsonProperty("has_more") boolean hasMore,
        @Nullable @JsonProperty("next_cursor") String nextCursor
) {
    public String getNextCursor() {
        return Objects.requireNonNullElse(nextCursor, "");
    }
}

