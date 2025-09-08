package secondbrain.infrastructure.gong.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongCallExtensiveContext(String system, List<GongCallExtensiveContextObject> objects) {
    public Optional<GongCallExtensiveContextObjectField> getObject(final String fieldName) {
        return objects == null ? Optional.empty() :
                objects.stream()
                        .flatMap(o -> o.getFields().stream())
                        .filter(f -> fieldName.equals(f.name()))
                        .findFirst();
    }
}
