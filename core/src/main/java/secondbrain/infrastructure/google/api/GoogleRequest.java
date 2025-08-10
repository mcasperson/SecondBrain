package secondbrain.infrastructure.google.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleRequest(List<GoogleRequestContents> contents,
                            @Nullable @JsonProperty("system_instruction") GoogleRequestSystemInstruction systemInstruction) {
    public GoogleRequest(List<GoogleRequestContents> contents) {
        this(contents, null);
    }

    public List<GoogleRequestContents> getContents() {
        return contents != null ? contents : List.of();
    }

    public String getPromptText() {
        return getContents().stream()
                .flatMap(content -> content.getParts().stream())
                .map(GoogleRequestContentsParts::getText)
                .collect(Collectors.joining("\n"));
    }
}
