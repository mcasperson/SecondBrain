package secondbrain.infrastructure.google.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleResponseCandidatesContentParts(String text) {
    public String getText() {
        return Objects.requireNonNullElse(text, "");
    }
}
