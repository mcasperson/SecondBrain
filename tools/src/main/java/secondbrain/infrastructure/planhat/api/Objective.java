package secondbrain.infrastructure.planhat.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Objective(
        @JsonProperty("_id") String id,
        String companyId,
        String companyName,
        String name,
        @Nullable Integer health,
        @Nullable Boolean sharedInPortal,
        @Nullable Map<String, Object> custom,
        @Nullable String createdAt,
        @Nullable String updatedAt
) {
}


