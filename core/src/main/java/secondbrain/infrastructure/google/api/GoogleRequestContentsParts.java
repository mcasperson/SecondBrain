package secondbrain.infrastructure.google.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleRequestContentsParts(String text) {
    public String getText() {
        return text != null ? text : "";
    }
}
