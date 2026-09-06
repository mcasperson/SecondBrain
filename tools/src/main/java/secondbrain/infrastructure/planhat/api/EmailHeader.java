package secondbrain.infrastructure.planhat.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EmailHeader(@Nullable String name, @Nullable String value) {
    public String getName() {
        return Objects.requireNonNullElse(name, "");
    }

    public String getValue() {
        return Objects.requireNonNullElse(value, "");
    }
}
