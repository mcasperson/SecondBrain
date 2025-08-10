package secondbrain.infrastructure.google.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleResponseCandidatesContent(List<GoogleResponseCandidatesContentParts> parts) {
    public List<GoogleResponseCandidatesContentParts> getParts() {
        return Objects.requireNonNullElse(parts, List.of());
    }
}
