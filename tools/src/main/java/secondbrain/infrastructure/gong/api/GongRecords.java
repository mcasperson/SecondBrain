package secondbrain.infrastructure.gong.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongRecords(
        int totalRecords,
        int currentPageSize,
        int currentPageNumber,
        @Nullable String cursor
) {
    public String getCursor() {
        return Objects.requireNonNullElse(cursor, "");
    }
}
