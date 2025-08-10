package secondbrain.infrastructure.google.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleResponseCandidates(GoogleResponseCandidatesContent content) {
    public GoogleResponseCandidatesContent getContent() {
        return content != null ? content : new GoogleResponseCandidatesContent(List.of());
    }
}
