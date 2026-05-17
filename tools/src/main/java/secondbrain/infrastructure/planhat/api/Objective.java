package secondbrain.infrastructure.planhat.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

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
    public Integer getHealth() {
        return Objects.requireNonNullElse(health, 0);
    }

    public boolean isSharedInPortal() {
        return Objects.requireNonNullElse(sharedInPortal, false);
    }

    public Map<String, Object> getCustom() {
        return Objects.requireNonNullElse(custom, Map.of());
    }

    public String getCreatedAt() {
        return Objects.requireNonNullElse(createdAt, "");
    }

    public String getUpdatedAt() {
        return Objects.requireNonNullElse(updatedAt, "");
    }
}


