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

    public Optional<GongCallExtensiveContext> getSystemContext(final String system) {
        return Objects.requireNonNullElse(context(), List.<GongCallExtensiveContext>of())
                .stream()
                .filter(c -> system.equals(c.system()))
                .findFirst();
    }

    public Optional<GongCallExtensiveContextObjectField> getSystemContext(final String system, final String objectType, final String fieldName) {
        return Objects.requireNonNullElse(context(), List.<GongCallExtensiveContext>of())
                .stream()
                .filter(c -> system.equals(c.system()))
                .flatMap(c -> c.objects().stream())
                .filter(o -> objectType.equals(o.objectType()))
                .flatMap(o -> o.getFields().stream())
                .filter(f -> fieldName.equals(f.name()))
                .findFirst();
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
        return party != null ? party.name() : "Unknown Speaker";
    }
}
