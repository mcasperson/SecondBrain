package secondbrain.infrastructure.planhat.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Opportunity(
        @JsonProperty("_id") String id,
        @Nullable String status,
        @Nullable String companyId,
        @Nullable String companyName,
        @Nullable String title,
        @Nullable String ownerId,
        @Nullable String sourceId,
        @Nullable Double mrr,
        @Nullable Double arr,
        @Nullable String salesStage,
        @Nullable String landingDate,
        @Nullable String closeDate,
        @Nullable String createdAt,
        @Nullable String updatedAt,
        @Nullable Map<String, Object> custom
) {
}

