package secondbrain.infrastructure.google.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleResponse(List<GoogleResponseCandidates> candidates) {

    public List<GoogleResponseCandidates> getCandidates() {
        return Objects.requireNonNullElse(candidates, List.of());
    }
}
