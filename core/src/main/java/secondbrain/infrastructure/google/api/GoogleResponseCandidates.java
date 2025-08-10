package secondbrain.infrastructure.google.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleResponseCandidates(GoogleResponseCandidatesContent content) {
}
