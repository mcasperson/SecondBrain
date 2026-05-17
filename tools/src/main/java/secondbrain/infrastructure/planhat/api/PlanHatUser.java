package secondbrain.infrastructure.planhat.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanHatUser(
        @JsonProperty("_id") String id,
        @Nullable String firstName,
        @Nullable String lastName
) {
    public String getFirstName() {
        return Objects.requireNonNullElse(firstName, "");
    }

    public String getLastName() {
        return Objects.requireNonNullElse(lastName, "");
    }

    public String getFullName() {
        return getFirstName() + (lastName != null ? " " + lastName : "");
    }
}

