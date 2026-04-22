package secondbrain.infrastructure.dovetail.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DovetailDataExport(
        String id,
        String type,
        String title,
        @JsonProperty("created_at") String createdAt,
        boolean deleted,
        @Nullable @JsonProperty("content_markdown") String contentMarkdown
) {
}

