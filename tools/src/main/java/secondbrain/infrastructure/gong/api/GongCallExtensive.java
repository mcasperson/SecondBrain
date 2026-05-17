package secondbrain.infrastructure.gong.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongCallExtensive(GongCallExtensiveMetadata metaData,
                                List<GongCallExtensiveContext> context,
                                List<GongCallExtensiveParty> parties) {

    private static final GongCallExtensiveMetadata EMPTY_METADATA = new GongCallExtensiveMetadata("", "", "");

    public GongCallExtensiveMetadata getMetaData() {
        return Objects.requireNonNullElse(metaData(), EMPTY_METADATA);
    }

    public List<GongCallExtensiveContext> getContext() {
        return Objects.requireNonNullElse(context(), List.of());
    }

    public String getSystemContextOrDefault(final String systemName, final String objectName, final String defaultValue) {
        return getSystemContext(systemName)
                .flatMap(c -> c.getObject(objectName))
                .map(f -> Objects.toString(f.value(), defaultValue))
                .orElse(defaultValue);
    }

    public Optional<GongCallExtensiveContext> getSystemContext(final String system) {
        return getContext().stream()
                .filter(c -> system.equals(c.system()))
                .findFirst();
    }

    public Optional<GongCallExtensiveContextObjectField> getSystemContext(final String system, final String objectType, final String fieldName) {
        return getContext().stream()
                .filter(c -> system.equals(c.system()))
                .flatMap(c -> c.getObjects().stream())
                .filter(o -> objectType.equals(o.objectType()))
                .flatMap(o -> o.getFields().stream())
                .filter(f -> fieldName.equals(f.name()))
                .findFirst();
    }

    public String getSystemContextValue(final String system, final String objectType, final String fieldName, final String defaultValue) {
        return getSystemContext(system, objectType, fieldName)
                .map(f -> Objects.toString(f.value(), defaultValue))
                .orElse(defaultValue);
    }

    @Nullable
    public GongCallExtensiveParty getPartyFromId(final String speakerId) {
        if (parties == null || parties.isEmpty() || speakerId == null || speakerId.isBlank()) {
            return null;
        }

        return parties.stream()
                .filter(Objects::nonNull)
                .filter(party -> speakerId.equals(party.speakerId()))
                .findFirst()
                .orElse(null);
    }

    public String getPartyNameFromId(final String partyId) {
        final GongCallExtensiveParty party = getPartyFromId(partyId);
        return party != null ? Objects.toString(party.name(), "Unknown Speaker") : "Unknown Speaker";
    }
}
