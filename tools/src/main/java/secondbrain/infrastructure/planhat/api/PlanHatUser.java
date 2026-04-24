package secondbrain.infrastructure.planhat.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanHatUser(
        @JsonProperty("_id") String id,
        @Nullable String firstName,
        @Nullable String lastName
) {
    public String getFullName() {
        return (firstName != null ? firstName : "") + (lastName != null ? " " + lastName : "");
    }
}

