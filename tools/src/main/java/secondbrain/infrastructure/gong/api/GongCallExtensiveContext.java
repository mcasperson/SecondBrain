package secondbrain.infrastructure.gong.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongCallExtensiveContext(String system, List<GongCallExtensiveContextObject> objects) {
    public List<GongCallExtensiveContextObject> getObjects() {
        return Objects.requireNonNullElse(objects, List.of());
    }

    public Optional<GongCallExtensiveContextObjectField> getObject(final String fieldName) {
        return getObjects().stream()
                .flatMap(o -> o.getFields().stream())
                .filter(f -> fieldName.equals(f.name()))
                .findFirst();
    }

    public Optional<GongCallExtensiveContextObjectField> getObject(final String objectType, final String fieldName) {
        return getObjects().stream()
                .filter(o -> o.objectType().equals(objectType))
                .flatMap(o -> o.getFields().stream())
                .filter(f -> fieldName.equals(f.name()))
                .findFirst();
    }
}
