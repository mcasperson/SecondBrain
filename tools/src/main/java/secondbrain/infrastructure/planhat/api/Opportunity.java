package secondbrain.infrastructure.planhat.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

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
    public String getStatus() {
        return Objects.requireNonNullElse(status, "");
    }

    public String getCompanyId() {
        return Objects.requireNonNullElse(companyId, "");
    }

    public String getCompanyName() {
        return Objects.requireNonNullElse(companyName, "");
    }

    public String getTitle() {
        return Objects.requireNonNullElse(title, "");
    }

    public String getOwnerId() {
        return Objects.requireNonNullElse(ownerId, "");
    }

    public String getSourceId() {
        return Objects.requireNonNullElse(sourceId, "");
    }

    public Double getMrr() {
        return Objects.requireNonNullElse(mrr, 0.0);
    }

    public Double getArr() {
        return Objects.requireNonNullElse(arr, 0.0);
    }

    public String getSalesStage() {
        return Objects.requireNonNullElse(salesStage, "");
    }

    public String getLandingDate() {
        return Objects.requireNonNullElse(landingDate, "");
    }

    public String getCloseDate() {
        return Objects.requireNonNullElse(closeDate, "");
    }

    public String getCreatedAt() {
        return Objects.requireNonNullElse(createdAt, "");
    }

    public String getUpdatedAt() {
        return Objects.requireNonNullElse(updatedAt, "");
    }

    public Map<String, Object> getCustom() {
        return Objects.requireNonNullElse(custom, Map.of());
    }
}

