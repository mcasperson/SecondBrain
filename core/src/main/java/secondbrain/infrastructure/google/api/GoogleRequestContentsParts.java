package secondbrain.infrastructure.google.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleRequestContentsParts(@Nullable String text) {
    public String getText() {
        return text != null ? text : "";
    }
}
