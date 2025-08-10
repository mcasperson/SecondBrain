package secondbrain.infrastructure.google.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleRequest(List<GoogleRequestContents> contents,
                            @Nullable @JsonProperty("system_instruction") GoogleRequestSystemInstruction systemInstruction) {
    public GoogleRequest(List<GoogleRequestContents> contents) {
        this(contents, null);
    }
}
