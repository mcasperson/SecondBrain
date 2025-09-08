package secondbrain.infrastructure.gong.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongCallExtensiveContextObject(String objectType, String objectId,
                                             List<GongCallExtensiveContextObjectField> fields) {

    public List<GongCallExtensiveContextObjectField> getFields() {
        return Objects.requireNonNullElse(fields, List.of());
    }
}
