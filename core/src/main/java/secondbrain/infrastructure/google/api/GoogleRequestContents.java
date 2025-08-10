package secondbrain.infrastructure.google.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleRequestContents(List<GoogleRequestContentsParts> parts) {
    public List<GoogleRequestContentsParts> getParts() {
        return parts != null ? parts : List.of();
    }
}
